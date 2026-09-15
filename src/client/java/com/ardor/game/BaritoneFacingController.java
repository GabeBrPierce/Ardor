package com.ardor.game;

import com.ardor.bridge.BaritoneNav;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * "Our character is walking in directions without looking in those directions. We should use the
 * human-like camera motion to face the direction we are moving." Baritone drives movement
 * directly (its own input handling, independent of this mod) but never touches the player's
 * yaw/pitch on its own in this setup -- confirmed live: navigating via Go Here / gohome / mine's
 * walk-to-target all moved the player in a straight line with the camera facing wherever it
 * happened to be pointed beforehand.
 *
 * Deliberately independent of PathfindingController's own baritoneNavGoal tracking -- Go Here
 * (GoHereMixin) calls BaritoneNav.goTo directly, bypassing PathfindingController entirely, so a
 * controller scoped to PathfindingController's own state wouldn't cover it. Watching
 * BaritoneNav.isPathing() directly covers every caller uniformly (mine, gohome, Go Here, and any
 * future direct BaritoneNav use) without duplicating this in each call site.
 *
 * Faces the player's own current velocity direction rather than the path's next waypoint or the
 * final goal -- Baritone doesn't expose "next waypoint" through the API surface already used here,
 * and velocity-direction naturally follows whatever curve the actual path takes (corners, dodges
 * around obstacles) without needing any of that. Same smoothLookAt easing every other controller
 * in this codebase uses for "don't snap the camera" -- see RotationUtil's own javadoc.
 */
public final class BaritoneFacingController {

    private BaritoneFacingController() {}

    private static final float FACING_TURN_RATE = 15f; // matches PathExecutor's own walking turn rate
    // Below this horizontal speed, velocity direction is mostly noise (standing still, a tiny nudge)
    // -- not worth spinning the camera to face it. ~0.05 blocks/tick, well under normal walk speed.
    private static final double MIN_SPEED_SQ = 0.0025;
    // Raw per-tick velocity direction is itself noisy (collision/edge-alignment corrections Baritone
    // makes while walking a straight-looking path) -- feeding it straight into smoothLookAt every
    // tick read as a visible camera "wiggle" while just walking forward. Smoothing the heading
    // itself with an exponential moving average before turning toward it removes that noise; a
    // lower weight means slower to react to genuine direction changes (corners) but steadier on a
    // straight line -- 0.15 favors steadiness since FACING_TURN_RATE/smoothLookAt's own easing
    // already handles catching up to real turns.
    private static final double HEADING_EMA_WEIGHT = 0.15;
    private static Double smoothedHeadX;
    private static Double smoothedHeadZ;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick(client);
            } catch (RuntimeException e) {
                System.err.println("[ardor] baritone facing failed: " + e);
            }
        });
    }

    private static void tick(Minecraft client) {
        if (!BaritoneNav.isPathing()) {
            smoothedHeadX = null;
            smoothedHeadZ = null;
            return;
        }
        LocalPlayer player = client.player;
        if (player == null) return;

        Vec3 v = player.getDeltaMovement();
        double speedSq = v.x * v.x + v.z * v.z;
        if (speedSq < MIN_SPEED_SQ) return;

        if (smoothedHeadX == null) {
            smoothedHeadX = v.x;
            smoothedHeadZ = v.z;
        } else {
            smoothedHeadX = smoothedHeadX + (v.x - smoothedHeadX) * HEADING_EMA_WEIGHT;
            smoothedHeadZ = smoothedHeadZ + (v.z - smoothedHeadZ) * HEADING_EMA_WEIGHT;
        }

        double targetX = player.getX() + smoothedHeadX * 10.0;
        double targetZ = player.getZ() + smoothedHeadZ * 10.0;
        RotationUtil.smoothLookAt(player, targetX, player.getEyeY(), targetZ, FACING_TURN_RATE);
    }
}
