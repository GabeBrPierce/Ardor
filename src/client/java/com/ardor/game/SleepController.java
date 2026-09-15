package com.ardor.game;

import com.ardor.client.ArdorMasterToggle;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Opportunistic auto-sleep: if it's night, the player isn't already in a bed, and isn't busy with
 * another reflex/action, right-clicks any bed that happens to be within a short radius to skip
 * the night (server-side, if enough players are asleep). This is a real mitigation for the
 * hostile-mob-spawn deaths flagged in TODO.md -- sleeping through the night removes most of the
 * mob-spawn-pressure window entirely.
 *
 * "Is it night" note: this MC version (26.1.2) replaced the classic
 * Level.isNight()/Level.getDayTime() day-night accessor with a data-driven WorldClock/
 * ClockTimeMarker registry system -- verified via javap against the real client/common jars that
 * neither method exists on Level anymore, and the only remaining time accessor
 * (ClockManager.getTotalTicks(Holder<WorldClock>)) requires resolving ClockTimeMarkers.NIGHT/DAY
 * registry holders and comparing tick ranges, which is out of scope for this single
 * self-contained file. Level.isDarkOutside() is used instead as the "is it night" check --
 * verified via bytecode inspection to be `!dimensionType().hasFixedTime() && skyDarken >= 4`,
 * which is a real, already-existing proxy for night. Known imprecision: it also goes true during
 * heavy sky-darkening rain, not just at night -- see TODO.md.
 *
 * Opportunistic only: does NOT navigate to a bed or place one. Only acts if a bed already happens
 * to be within SEARCH_RADIUS and within interaction range -- e.g. the player is already standing
 * in their base. If too far from a found bed, or no bed is nearby, this does nothing and waits for
 * conditions to change. See GameActionController.handlePlace/handleUse for the
 * useItemOn/BlockHitResult idiom reused here for the actual right-click.
 */
public final class SleepController {

    private SleepController() {}

    private static final int SEARCH_RADIUS = 6;
    private static final double INTERACT_RANGE = 3.0;

    // Gates the whole check-and-maybe-sleep cycle (not just the useItemOn call) so this isn't
    // rescanning a 13x13x13 cube of blocks every tick while it's night and no bed is nearby, on
    // top of not spamming right-clicks every tick once one is.
    private static final int COOLDOWN_TICKS = 60; // 3s between attempts, regardless of outcome

    private static int tick;
    private static int lastAttemptTick = Integer.MIN_VALUE;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(SleepController::onTick);
        ArdorMasterToggle.register(SleepController::cancel);
    }

    /** stopSleepInBed alone only updates local client state (verified via javap) -- also sends the STOP_SLEEPING command a real sneak-to-wake sends, so the server actually wakes the player too. */
    public static void cancel() {
        LocalPlayer self = Minecraft.getInstance().player;
        if (self == null || !self.isSleeping()) return;
        self.connection.send(new ServerboundPlayerCommandPacket(self, ServerboundPlayerCommandPacket.Action.STOP_SLEEPING));
        self.stopSleepInBed(false, true);
    }

    private static void onTick(Minecraft client) {
        try {
            tickInner(client);
        } catch (RuntimeException e) {
            System.err.println("[ardor] sleep controller failed: " + e);
        }
    }

    private static void tickInner(Minecraft client) {
        if (!ArdorMasterToggle.isEnabled()) return;
        tick++;
        if (tick - lastAttemptTick < COOLDOWN_TICKS) return;

        LocalPlayer self = client.player;
        Level level = client.level;
        if (self == null || level == null) return;
        if (self.isSleeping()) return;
        if (GameActionController.isBusy()) return;
        if (!level.isDarkOutside()) return;

        lastAttemptTick = tick;

        BlockPos bed = findNearbyBed(level, self.blockPosition());
        if (bed == null) return;
        if (self.position().distanceTo(Vec3.atCenterOf(bed)) > INTERACT_RANGE) return;

        RotationUtil.lookAtExact(self, bed);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(bed), Direction.UP, bed, false);
        client.gameMode.useItemOn(self, InteractionHand.MAIN_HAND, hit);
    }

    private static BlockPos findNearbyBed(Level level, BlockPos center) {
        BlockPos nearest = null;
        double nearestDistSqr = Double.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!(level.getBlockState(pos).getBlock() instanceof BedBlock)) continue;
                    double distSqr = pos.distSqr(center);
                    if (distSqr < nearestDistSqr) {
                        nearestDistSqr = distSqr;
                        nearest = pos;
                    }
                }
            }
        }
        return nearest;
    }
}
