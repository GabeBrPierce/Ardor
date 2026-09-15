package com.ardor.game;

import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.input.Input;
import com.ardor.bridge.BaritoneNav;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * "Movement is too baritone-y ... add some variance ... make it slightly more messy." Layers
 * human-like imprecision on top of Baritone's own movement each tick it's pathing, via the same
 * real IInputOverrideHandler Baritone itself drives movement through (BaritoneNav.inputOverride,
 * confirmed real via javap against baritone-api-fabric-1.18.0.jar) -- never touches Baritone's own
 * path planning, only overrides input state for the current tick, same "override on top" pattern
 * GameActionController's combat strafing already established against a Baritone-driven follow
 * (including that code's own acknowledged risk: whichever tick listener runs second for a given
 * tick wins for that tick -- not independently re-verified here, see TODO.md).
 *
 * Three effects, independently gated, all active only while BaritoneNav.isPathing():
 *  - Speed variance: periodically forces SPRINT off for a short window even if Baritone wants to
 *    sprint, so travel doesn't read as a metronomically constant pace.
 *  - Misjumps ascending a step: when a rising step is detected ahead and the player isn't already
 *    jumping, occasionally forces an early JUMP pulse while still 1-2 blocks short of it (too far
 *    to actually clear the step) -- looks like a mistimed jump; Baritone's own real jump (never
 *    overridden by this class once the player is actually adjacent) then gets them up for real
 *    moments later, per the request ("jump too early on purpose and jump again moments later").
 *  - Crouch near risky terrain: forces SNEAK on while both perpendicular sides of the player's
 *    current heading have no solid ground within a few blocks below (a narrow bridge/ledge with a
 *    drop on both sides), off again once clear.
 */
public final class MovementVarianceController {

    private MovementVarianceController() {}

    private static final Random RANDOM = new Random();

    private static int ticksUntilNextPaceChange = 0;
    private static int easeOffTicksLeft = 0;

    private static BlockPos lastMisjumpStep;
    private static int misjumpTicksLeft = 0;

    private static boolean crouching = false;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick(client);
            } catch (RuntimeException e) {
                System.err.println("[ardor] movement variance tick failed: " + e);
            }
        });
    }

    private static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null || !BaritoneNav.isPathing()) {
            resetCrouch();
            return;
        }

        IInputOverrideHandler input = BaritoneNav.inputOverride();
        applySpeedVariance(input);
        applyMisjump(client, player, input);
        applyCrouchNearRisk(client, player, input);
    }

    private static void resetCrouch() {
        if (!crouching) return;
        crouching = false;
        BaritoneNav.inputOverride().setInputForceState(Input.SNEAK, false);
    }

    private static void applySpeedVariance(IInputOverrideHandler input) {
        if (easeOffTicksLeft > 0) {
            easeOffTicksLeft--;
            input.setInputForceState(Input.SPRINT, false);
            return;
        }
        if (ticksUntilNextPaceChange-- > 0) return;
        ticksUntilNextPaceChange = 60 + RANDOM.nextInt(120); // roughly every 3-9s
        easeOffTicksLeft = 8 + RANDOM.nextInt(15); // 0.4-1.1s of walking instead of sprinting
    }

    private static void applyMisjump(Minecraft client, LocalPlayer player, IInputOverrideHandler input) {
        if (misjumpTicksLeft > 0) {
            misjumpTicksLeft--;
            input.setInputForceState(Input.JUMP, true);
            if (misjumpTicksLeft == 0) input.setInputForceState(Input.JUMP, false);
            return;
        }
        // Baritone's own real jump is either already in progress or the player isn't grounded to
        // begin one -- don't add a second jump input on top of either case.
        if (!player.onGround() || input.isInputForcedDown(Input.JUMP)) return;

        BlockPos step = risingStepAhead(client.level, player);
        if (step == null || step.equals(lastMisjumpStep)) return;

        double distSq = player.position().distanceToSqr(step.getX() + 0.5, player.getY(), step.getZ() + 0.5);
        if (distSq < 1.2 * 1.2 || distSq > 2.6 * 2.6) return; // only while still approaching, not already adjacent to it

        if (RANDOM.nextInt(6) != 0) return; // most steps get climbed cleanly -- this is meant to be occasional, not every time
        lastMisjumpStep = step;
        misjumpTicksLeft = 3;
    }

    /** One block ahead along the player's current horizontal movement heading, checked for the shape of a single ascending step: solid at foot level, clear for two blocks above (room to jump into and land on top of). */
    private static BlockPos risingStepAhead(Level level, LocalPlayer player) {
        Vec3 velocity = player.getDeltaMovement();
        if (velocity.horizontalDistanceSqr() < 0.003) return null;
        Direction facing = Direction.getApproximateNearest(velocity.x, 0, velocity.z);
        BlockPos ahead = player.blockPosition().relative(facing);
        BlockPos above = ahead.above();
        if (!level.getBlockState(ahead).isAir()
                && level.getBlockState(above).isAir()
                && level.getBlockState(above.above()).isAir()) {
            return ahead;
        }
        return null;
    }

    private static final int DROPOFF_CHECK_DEPTH = 3;

    private static void applyCrouchNearRisk(Minecraft client, LocalPlayer player, IInputOverrideHandler input) {
        Level level = client.level;
        Vec3 velocity = player.getDeltaMovement();
        if (velocity.horizontalDistanceSqr() < 0.003) {
            if (crouching) {
                crouching = false;
                input.setInputForceState(Input.SNEAK, false);
            }
            return;
        }
        Direction facing = Direction.getApproximateNearest(velocity.x, 0, velocity.z);
        BlockPos feet = player.blockPosition();
        boolean risky = isDropoff(level, feet, facing.getCounterClockWise()) && isDropoff(level, feet, facing.getClockWise());
        if (risky != crouching) {
            crouching = risky;
            input.setInputForceState(Input.SNEAK, risky);
        }
    }

    /** True if the DROPOFF_CHECK_DEPTH blocks below the tile one step to `side` are all air -- a real drop, not just a shallow dip. */
    private static boolean isDropoff(Level level, BlockPos feet, Direction side) {
        BlockPos pos = feet.relative(side);
        for (int i = 0; i < DROPOFF_CHECK_DEPTH; i++) {
            if (!level.getBlockState(pos).isAir()) return false;
            pos = pos.below();
        }
        return true;
    }
}
