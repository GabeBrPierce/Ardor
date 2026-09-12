package com.ardor.game;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec2;

import java.util.Random;

/**
 * "Block when a hit is about to land, then immediately swing back, then block again" -- the
 * manuver the user asked for by name. Complements (does not replace) the steady
 * `GameActionController.attackUntilDead` loop: that loop already keeps swinging on its own
 * bell-curve rhythm, this controller's only job is to interleave real shield-up windows into
 * that ongoing fight when melee damage is close to unavoidable.
 *
 * Real per-mob telegraph reading (is THIS zombie mid-swing right now) would need each hostile
 * mob class's own attack-animation/cooldown state, which varies per mob type and isn't
 * practically doable in one pass -- see the class-level scope note in TODO.md. Uses a practical
 * proximity+cooldown heuristic instead: once a hostile is within melee-unavoidable range, assume
 * it can land a hit roughly on its own attack rhythm and raise the shield periodically to catch
 * it, same "natural-feeling irregularity over a robotic metronome" preference
 * `GameActionController`'s bell-curve attack interval was built around (see its own comment).
 *
 * Same always-on tick-hook shape as AutoFleeController/ArrowDodgeController: own END_CLIENT_TICK
 * hook, try/catch-never-crash wrapper, own InputSwapManager-arbitrated input hold (player just
 * stands and trades rather than wandering off mid-block).
 *
 * The "attackUntilDead is a loop, not a single swing" problem: GameActionController exposes no
 * one-shot attack entry point from this package, and this pass isn't allowed to add one. Rather
 * than fight the till-dead loop for control of exactly when a swing happens, this treats it as
 * the swing mechanism: `attackEntityUntilDead(target)` both (re)targets AND resets the loop's
 * internal cooldown to 0 (see its own comment -- "Calling this again just retargets the one
 * shared ticker"), which fires an immediate swing on the very next tick. So "attack phase" here
 * means: lower the shield and (re)call attackEntityUntilDead once, letting its own bell-curve
 * ticker keep swinging in the background for the rest of that phase; "block phase" means: raise
 * the shield and leave the till-dead ticker running unattended (it may or may not get a swing in
 * before the next block phase starts -- an accepted imprecision, not worth adding a pause/resume
 * hook to GameActionController for a single controller's benefit this pass).
 */
public final class ParryController {

    private ParryController() {}

    // "Unavoidable" melee range -- vanilla melee mobs need to be adjacent to hit at all, so this
    // sits just past that. Re-evaluated with hysteresis (DISENGAGE_RANGE) so parry mode doesn't
    // flicker on/off right at the boundary while the target shuffles in and out of ENGAGE_RANGE.
    private static final double ENGAGE_RANGE = 2.5;
    private static final double DISENGAGE_RANGE = 3.5;

    private static final float PARRY_TURN_RATE = 25f; // matches GameActionController's combat turn rate

    // Block phase: "raise a shield ~4-6 ticks out of every ~15-20 tick cycle" per the user's own
    // framing. Sampled per-phase (not a fixed constant) for the same reason GameActionController
    // samples its attack interval from a Gaussian -- a perfectly even cycle reads as robotic.
    private static final Random RANDOM = new Random();
    private static final double BLOCK_TICKS_MEAN = 5.0;
    private static final double BLOCK_TICKS_STDDEV = 0.6;
    private static final int BLOCK_TICKS_MIN = 4;
    private static final int BLOCK_TICKS_MAX = 6;

    private static final double CYCLE_TICKS_MEAN = 17.0;
    private static final double CYCLE_TICKS_STDDEV = 1.5;
    private static final int CYCLE_TICKS_MIN = 15;
    private static final int CYCLE_TICKS_MAX = 20;

    private static Entity parryTarget;
    private static InteractionHand shieldHand;
    private static boolean blockPhase;
    private static int phaseTicksRemaining;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ParryController::onTick);
    }

    private static void onTick(Minecraft client) {
        try {
            tickInner(client);
        } catch (RuntimeException e) {
            System.err.println("[ardor] parry failed: " + e);
            stopParrying();
        }
    }

    private static void tickInner(Minecraft client) {
        LocalPlayer self = client.player;
        if (self == null || client.level == null) {
            stopParrying();
            return;
        }

        if (parryTarget != null) {
            runParryTick(self);
            return;
        }

        // Only engage parry mode if a hostile is already melee-close AND a shield is actually
        // available -- otherwise this does nothing and the plain attackUntilDead loop (triggered
        // elsewhere, e.g. defend-on-attack) handles combat normally, per the task's scope.
        Entity threat = nearestHostile(self, ENGAGE_RANGE);
        if (threat == null) return;
        InteractionHand hand = findShieldHand(self);
        if (hand == null) return;

        if (!InputSwapManager.tryAcquire(InputSwapManager.Owner.PARRY)) return; // pre-empted, retry next tick

        parryTarget = threat;
        shieldHand = hand;
        beginBlockPhase(self);
    }

    private static void runParryTick(LocalPlayer self) {
        if (!InputSwapManager.isOwnedBy(InputSwapManager.Owner.PARRY)) {
            // Pre-empted by a higher-priority controller (arrow dodge, auto-flee) -- stop cleanly.
            stopParrying();
            return;
        }
        if (!parryTarget.isAlive() || self.distanceTo(parryTarget) > DISENGAGE_RANGE) {
            stopParrying();
            return;
        }
        if (findShieldHand(self) == null) {
            // Shield swapped out mid-fight (e.g. an `equip` action) -- nothing left to parry with.
            stopParrying();
            return;
        }

        RotationUtil.smoothLookAtHumanized(self, parryTarget.getX(), parryTarget.getEyeY(), parryTarget.getZ(), PARRY_TURN_RATE);
        holdStill(self);

        if (--phaseTicksRemaining > 0) return;

        if (blockPhase) {
            beginAttackPhase(self);
        } else {
            beginBlockPhase(self);
        }
    }

    private static void beginBlockPhase(LocalPlayer self) {
        blockPhase = true;
        phaseTicksRemaining = sampleBlockTicks();
        self.startUsingItem(shieldHand);
    }

    private static void beginAttackPhase(LocalPlayer self) {
        blockPhase = false;
        int cycleTicks = sampleCycleTicks();
        int blockTicks = sampleBlockTicks();
        phaseTicksRemaining = Math.max(1, cycleTicks - blockTicks); // rest of the cycle, shield down
        if (self.isUsingItem()) self.stopUsingItem();
        GameActionController.attackEntityUntilDead(parryTarget); // retarget resets the till-dead ticker to swing next tick
    }

    private static void holdStill(LocalPlayer self) {
        self.input.keyPresses = new Input(false, false, false, false, false, false, false);
        self.input.moveVector = Vec2.ZERO;
    }

    private static InteractionHand findShieldHand(LocalPlayer self) {
        if (self.getOffhandItem().getItem() == Items.SHIELD) return InteractionHand.OFF_HAND;
        if (self.getMainHandItem().getItem() == Items.SHIELD) return InteractionHand.MAIN_HAND;
        return null;
    }

    private static Entity nearestHostile(LocalPlayer self, double radius) {
        return SelectorResolver.resolveOne("@e[category=hostile,distance=" + radius + ",sort=nearest,limit=1]");
    }

    private static int sampleBlockTicks() {
        double sample = BLOCK_TICKS_MEAN + RANDOM.nextGaussian() * BLOCK_TICKS_STDDEV;
        long rounded = Math.round(sample);
        return (int) Math.max(BLOCK_TICKS_MIN, Math.min(BLOCK_TICKS_MAX, rounded));
    }

    private static int sampleCycleTicks() {
        double sample = CYCLE_TICKS_MEAN + RANDOM.nextGaussian() * CYCLE_TICKS_STDDEV;
        long rounded = Math.round(sample);
        return (int) Math.max(CYCLE_TICKS_MIN, Math.min(CYCLE_TICKS_MAX, rounded));
    }

    private static void stopParrying() {
        if (parryTarget == null) return;
        LocalPlayer self = Minecraft.getInstance().player;
        if (self != null && self.isUsingItem()) self.stopUsingItem(); // don't leave the shield stuck up
        parryTarget = null;
        shieldHand = null;
        InputSwapManager.release(InputSwapManager.Owner.PARRY);
    }
}
