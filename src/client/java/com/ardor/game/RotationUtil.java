package com.ardor.game;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

import java.util.Random;

/**
 * Shared smooth look-at: turns the player's yaw/pitch toward a target at a
 * capped degrees-per-tick rate instead of snapping to the exact bearing
 * every tick. Previously every rotation in this codebase (PathExecutor's
 * walking yaw, BlockBreaker's mining lookAt) set setYRot/setXRot to the
 * exact computed angle each tick -- correct, but visibly robotic (instant
 * snap-turns) since nothing interpolated between ticks.
 *
 * Mth.wrapDegrees(float) normalizes to (-180, 180], which is what makes
 * "shortest path" turning work correctly across the 180/-180 wrap (e.g.
 * turning from 179 degrees to -179 degrees should step +2, not -358).
 *
 * stepTowards eases out near the target instead of turning at a constant
 * rate then snapping the instant it's in range -- research on natural
 * mouse/look movement (done alongside adding a bell-curve attack interval
 * to GameActionController) consistently points at ease-in-out as the
 * closest match to how a human actually aims: fast while far off, slowing
 * as it approaches, not a metronome-constant sweep that then teleports the
 * last few degrees. A proportional step (a fraction of whatever's left,
 * capped at maxDegPerTick) gives exactly that shape for free: it's already
 * capped at the max rate while far off, and naturally decelerates as the
 * remaining angle shrinks below what the cap alone would produce.
 */
public final class RotationUtil {

    private RotationUtil() {}

    public static void smoothLookAt(LocalPlayer player, double targetX, double targetY, double targetZ, float maxDegPerTick) {
        double dx = targetX - player.getX();
        double dy = targetY - player.getEyeY();
        double dz = targetZ - player.getZ();
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float desiredPitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(dy, distXZ)), -90.0, 90.0);

        player.setYRot(stepTowards(player.getYRot(), desiredYaw, maxDegPerTick));
        player.setXRot(stepTowards(player.getXRot(), desiredPitch, maxDegPerTick));
        player.setYHeadRot(player.getYRot());
    }

    public static void smoothLookAt(LocalPlayer player, BlockPos target, float maxDegPerTick) {
        smoothLookAt(player, target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, maxDegPerTick);
    }

    // "It worries me how closely the player tracks target mobs. Its wayyyy to robotic... there
    // should be a smooth human-like motion to literally everything." smoothLookAt already eases
    // in/out instead of snapping, but converges on a mathematically exact bearing every time --
    // the same distance/angle always produces the identical trajectory. This adds a small,
    // per-tick Gaussian wobble to the DESIRED bearing itself (not the current one, so it can't
    // accumulate/drift) -- small enough that it never visibly misses the real target, just keeps
    // the path there from being perfectly deterministic. For combat tracking specifically (see
    // GameActionController/ParryController) -- not used for one-shot/non-combat look-ats, where a
    // clean curve toward a static point isn't the thing that reads as robotic.
    private static final Random RANDOM = new Random();
    private static final float JITTER_STDDEV_DEG = 0.6f;

    public static void smoothLookAtHumanized(LocalPlayer player, double targetX, double targetY, double targetZ, float maxDegPerTick) {
        double dx = targetX - player.getX();
        double dy = targetY - player.getEyeY();
        double dz = targetZ - player.getZ();
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz)) + (float) (RANDOM.nextGaussian() * JITTER_STDDEV_DEG);
        float desiredPitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(dy, distXZ)) + RANDOM.nextGaussian() * JITTER_STDDEV_DEG, -90.0, 90.0);

        player.setYRot(stepTowards(player.getYRot(), desiredYaw, maxDegPerTick));
        player.setXRot(stepTowards(player.getXRot(), desiredPitch, maxDegPerTick));
        player.setYHeadRot(player.getYRot());
    }

    /**
     * Snaps straight to face a target, no rate limit -- for one-shot actions
     * (place/use/attack-once) that dispatch and complete within a single
     * tick, where there's no multi-tick window to gradually turn within.
     * smoothLookAt is for anything that runs across several ticks (walking,
     * mining, attack-until-dead); this is for everything else.
     */
    public static void lookAtExact(LocalPlayer player, double targetX, double targetY, double targetZ) {
        double dx = targetX - player.getX();
        double dy = targetY - player.getEyeY();
        double dz = targetZ - player.getZ();
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(dy, distXZ)), -90.0, 90.0);
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.setYHeadRot(yaw);
    }

    public static void lookAtExact(LocalPlayer player, BlockPos target) {
        lookAtExact(player, target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
    }

    public static void lookAtExact(LocalPlayer player, Entity target) {
        lookAtExact(player, target.getX(), target.getEyeY(), target.getZ());
    }

    /** True once the player's current facing is within thresholdDeg of target on both axes -- lets a caller wait for smoothLookAt to actually arrive before doing something one-shot (e.g. an instant creative-mode block break) instead of acting the instant it starts turning. */
    public static boolean isFacing(LocalPlayer player, BlockPos target, float thresholdDeg) {
        double dx = (target.getX() + 0.5) - player.getX();
        double dy = (target.getY() + 0.5) - player.getEyeY();
        double dz = (target.getZ() + 0.5) - player.getZ();
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float desiredPitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(dy, distXZ)), -90.0, 90.0);
        return Math.abs(Mth.wrapDegrees(desiredYaw - player.getYRot())) <= thresholdDeg
                && Math.abs(Mth.wrapDegrees(desiredPitch - player.getXRot())) <= thresholdDeg;
    }

    // Fraction of the remaining angle closed per tick once inside the ease zone (below maxStep/EASE_FACTOR
    // degrees remaining) -- 0.35 reaches ~99% of the way there in about 12 ticks (0.6s at 20tps) from the
    // point easing kicks in, which reads as a natural deceleration rather than a lingering crawl.
    private static final float EASE_FACTOR = 0.35f;
    private static final float SNAP_EPSILON_DEG = 0.5f; // close enough to stop easing and land exactly on target, rather than asymptotically approaching it forever

    private static float stepTowards(float current, float target, float maxStep) {
        float delta = Mth.wrapDegrees(target - current);
        if (Math.abs(delta) <= SNAP_EPSILON_DEG) return Mth.wrapDegrees(target);
        float step = Mth.clamp(delta * EASE_FACTOR, -maxStep, maxStep);
        return Mth.wrapDegrees(current + step);
    }
}
