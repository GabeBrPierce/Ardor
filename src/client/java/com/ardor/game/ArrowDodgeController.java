package com.ardor.game;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * Reflex response to inbound arrows. Baritone's pathing and every other
 * controller in this codebase (AutoFleeController, PathExecutor, ...) have no
 * concept of projectiles -- they react to blocks and entities-as-obstacles,
 * not fast-moving hitscan-ish entities. This is a separate, narrow reflex:
 * predict each live AbstractArrow's closest approach to the player using its
 * current velocity as a short-window linear estimate, and respond one of two
 * ways depending on how much warning there is:
 *
 *   - 2-14 ticks out (REACT_MIN_TICKS..REACT_MAX_TICKS): enough time to
 *     physically react -- strafe perpendicular to the arrow's flight path for
 *     a few ticks (findThreat/startDodging).
 *   - under 2 ticks out but still genuinely incoming: too close to sidestep
 *     in time, but if a shield is in either hand, raise it in place for a
 *     few ticks to cover the impact instead of eating the hit for free
 *     (findCloseThreat/startBlocking). No shield in this window means no
 *     response is possible -- same as a real player caught flat-footed.
 *
 * Same always-on tick-hook pattern as AutoFleeController. The player.input
 * swap itself now goes through InputSwapManager (shared arbitration point for
 * every input-swapping controller in this codebase) instead of a private
 * originalInput/inputSwapped pair -- see InputSwapManager's javadoc for the
 * priority order. This controller is ARROW_DODGE, the highest priority, so it
 * is never pre-empted mid-dodge or mid-block -- both are the same reflex
 * category, just two different responses to the same threat.
 */
public final class ArrowDodgeController {

    private ArrowDodgeController() {}

    private static final double SCAN_RADIUS = 24.0; // arrows travel ~1-3 blocks/tick; wide enough to catch one fired from across a small clearing

    // "On a collision course": at closest approach the arrow passes within this many blocks of the
    // player's current position. Player hitbox is 0.6 wide -- 1.2 gives margin for the linear estimate
    // being wrong (arrows actually decelerate/drop under gravity each tick; this treats velocity as
    // constant over the short prediction window, which is close enough that few ticks out).
    private static final double MISS_DISTANCE_THRESHOLD = 1.2;

    private static final double REACT_MIN_TICKS = 2.0;  // closer than this, no time to physically react -- don't bother starting a dodge that can't land before impact
    private static final double REACT_MAX_TICKS = 14.0; // further out than this (~0.7s), not yet urgent -- re-evaluate next tick instead of committing early to a stale prediction

    private static final int DODGE_TICKS = 6; // how long to hold the sidestep once triggered
    private static final int BLOCK_TICKS = 6; // how long to hold the shield up once triggered -- covers the sub-2-tick impact window with margin, then releases

    private static int dodgeTicksRemaining;
    private static double dodgeStrafe;
    private static double dodgeForward;
    private static boolean dodgeJump;

    private static int blockTicksRemaining;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ArrowDodgeController::onTick);
    }

    private static void onTick(Minecraft client) {
        try {
            tickInner(client);
        } catch (RuntimeException e) {
            System.err.println("[ardor] arrow dodge failed: " + e);
            stopDodging();
            stopBlocking();
        }
    }

    private static void tickInner(Minecraft client) {
        LocalPlayer self = client.player;
        ClientLevel level = client.level;
        if (self == null || level == null) {
            stopDodging();
            stopBlocking();
            return;
        }

        if (dodgeTicksRemaining > 0) {
            if (!InputSwapManager.isOwnedBy(InputSwapManager.Owner.ARROW_DODGE)) {
                // Should not happen (arrow dodge is top priority), but stop cleanly rather than
                // keep writing to input we no longer control.
                stopDodging();
                return;
            }
            dodgeTicksRemaining--;
            if (dodgeTicksRemaining == 0) {
                stopDodging();
                return;
            }
            applyDodgeInput(self);
            return;
        }

        if (blockTicksRemaining > 0) {
            if (!InputSwapManager.isOwnedBy(InputSwapManager.Owner.ARROW_DODGE)) {
                // Same as above -- should not happen (same top priority), but don't keep the
                // shield up (or keep writing input) once we no longer actually own it.
                stopBlocking();
                return;
            }
            blockTicksRemaining--;
            if (blockTicksRemaining == 0) {
                stopBlocking();
                return;
            }
            applyBlockInput(self);
            return;
        }

        AbstractArrow threat = findThreat(self, level);
        if (threat != null) {
            startDodging(self, threat);
            return;
        }

        // Too close to sidestep in time -- only worth checking (and only possible to act on) if
        // a shield is actually available to raise instead.
        AbstractArrow closeThreat = findCloseThreat(self, level);
        if (closeThreat == null) return;
        InteractionHand shieldHand = findShieldHand(self);
        if (shieldHand == null) return; // no shield, no dodge window -- nothing to do, take the hit
        startBlocking(self, shieldHand);
    }

    private static AbstractArrow findThreat(LocalPlayer self, ClientLevel level) {
        return findThreatInWindow(self, level, REACT_MIN_TICKS, REACT_MAX_TICKS);
    }

    /**
     * Same collision-course prediction as findThreat, but for the window too close to physically
     * dodge (0 <= t < REACT_MIN_TICKS, i.e. still genuinely incoming rather than already past).
     * A sidestep can't land in time here, but a shield raised now still can.
     */
    private static AbstractArrow findCloseThreat(LocalPlayer self, ClientLevel level) {
        return findThreatInWindow(self, level, 0.0, REACT_MIN_TICKS);
    }

    private static AbstractArrow findThreatInWindow(LocalPlayer self, ClientLevel level, double tMin, double tMax) {
        AABB box = self.getBoundingBox().inflate(SCAN_RADIUS);
        AbstractArrow best = null;
        double bestTime = Double.MAX_VALUE;

        for (AbstractArrow arrow : level.getEntitiesOfClass(AbstractArrow.class, box,
                a -> a.isAlive() && a.getOwner() != self && a.getDeltaMovement().lengthSqr() > 0.01)) {
            Vec3 rel = self.position().subtract(arrow.position());
            Vec3 v = arrow.getDeltaMovement();
            double vv = v.dot(v);
            double t = rel.dot(v) / vv; // ticks until closest approach, treating arrow velocity as constant
            if (t < tMin || t > tMax) continue;

            double miss = rel.subtract(v.scale(t)).length();
            if (miss > MISS_DISTANCE_THRESHOLD) continue;

            if (t < bestTime) {
                bestTime = t;
                best = arrow;
            }
        }
        return best;
    }

    /** Which hand (if either) is holding a shield -- main hand checked first, arbitrarily, since either works identically. Null if neither hand has one. */
    private static InteractionHand findShieldHand(LocalPlayer self) {
        if (self.getMainHandItem().getItem() == Items.SHIELD) return InteractionHand.MAIN_HAND;
        if (self.getOffhandItem().getItem() == Items.SHIELD) return InteractionHand.OFF_HAND;
        return null;
    }

    private static void startDodging(LocalPlayer self, AbstractArrow threat) {
        Vec3 v = threat.getDeltaMovement();
        // Perpendicular to the arrow's horizontal flight path -- either direction clears the line
        // equally, so pick the one biased toward where the player already sits relative to that line
        // (smaller net repositioning) rather than an arbitrary fixed side.
        Vec3 rel = self.position().subtract(threat.position());
        double cross = rel.x * v.z - rel.z * v.x;
        double px = cross >= 0 ? -v.z : v.z;
        double pz = cross >= 0 ? v.x : -v.x;
        double len = Math.sqrt(px * px + pz * pz);
        if (len < 1.0e-6) {
            stopDodging();
            return;
        }
        px /= len;
        pz /= len;

        Vec2 local = worldToLocalStrafe(px, pz, self.getYRot());
        dodgeStrafe = local.x;
        dodgeForward = local.y;
        dodgeJump = self.onGround();

        // Top priority -- always succeeds -- but guard anyway rather than assume.
        if (!InputSwapManager.tryAcquire(InputSwapManager.Owner.ARROW_DODGE)) return;
        dodgeTicksRemaining = DODGE_TICKS;
        applyDodgeInput(self);
    }

    /** World-space horizontal unit vector -> (strafe, forward) in the player's own facing, matching the moveVector convention every other input-swapping controller in this codebase uses (positive x = right, positive y = forward). */
    private static Vec2 worldToLocalStrafe(double wx, double wz, float yawDeg) {
        double yaw = Math.toRadians(yawDeg);
        double forward = -wx * Math.sin(yaw) + wz * Math.cos(yaw);
        double strafe = -(wx * Math.cos(yaw) + wz * Math.sin(yaw));
        return new Vec2((float) strafe, (float) forward);
    }

    private static void applyDodgeInput(LocalPlayer self) {
        boolean left = dodgeStrafe < -0.1;
        boolean right = dodgeStrafe > 0.1;
        boolean forward = dodgeForward > 0.1;
        boolean backward = dodgeForward < -0.1;
        self.input.keyPresses = new Input(forward, backward, left, right, dodgeJump, false, false);
        self.input.moveVector = new Vec2((float) dodgeStrafe, (float) dodgeForward).normalized();
    }

    private static void stopDodging() {
        dodgeTicksRemaining = 0;
        InputSwapManager.release(InputSwapManager.Owner.ARROW_DODGE);
    }

    /**
     * Raises the shield in place -- no sidestep, just hold still and block. Uses
     * LivingEntity#startUsingItem (overridden on LocalPlayer to also notify the server), the same
     * "held" mechanism vanilla's own right-click-and-hold input drives -- unlike the single-shot
     * gameMode.useItem/useItemOn calls the rest of this codebase uses for one-off right-clicks,
     * this stays "in use" (shield up) across ticks until stopUsingItem() is called, which is what
     * a multi-tick block needs.
     */
    private static void startBlocking(LocalPlayer self, InteractionHand shieldHand) {
        // Top priority -- always succeeds -- but guard anyway rather than assume.
        if (!InputSwapManager.tryAcquire(InputSwapManager.Owner.ARROW_DODGE)) return;
        self.startUsingItem(shieldHand);
        blockTicksRemaining = BLOCK_TICKS;
        applyBlockInput(self);
    }

    /** Hold still while blocking -- matches real shield use (you can move while blocking, but the point here is just to eat the hit safely, not reposition). */
    private static void applyBlockInput(LocalPlayer self) {
        self.input.keyPresses = new Input(false, false, false, false, false, false, false);
        self.input.moveVector = Vec2.ZERO;
    }

    private static void stopBlocking() {
        blockTicksRemaining = 0;
        LocalPlayer self = Minecraft.getInstance().player;
        if (self != null) self.stopUsingItem();
        InputSwapManager.release(InputSwapManager.Owner.ARROW_DODGE);
    }
}
