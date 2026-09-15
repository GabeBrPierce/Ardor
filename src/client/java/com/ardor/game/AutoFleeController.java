package com.ardor.game;

import java.util.ArrayDeque;
import com.ardor.client.ArdorMasterToggle;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * Safety valve for the defend-on-attack reflex (RegionCombatController's Reactive/Proactive tiers): that
 * reflex always fights back regardless of how outmatched the bot is, which caused real,
 * unintended deaths this session (kept fighting in a mob-infested area instead of retreating,
 * losing items each time). This is the missing override -- when health is critically low, stop
 * whatever attack is in progress and run away from the nearest hostile instead.
 *
 * Two independent triggers, either of which starts fleeing:
 *  1. Absolute threshold: current health below FLEE_HEALTH_THRESHOLD. Catches gradual attrition
 *     (chip damage grinding health down over many ticks).
 *  2. Burst-damage / rate-of-loss: health dropped by more than BURST_DAMAGE_THRESHOLD within the
 *     last WINDOW_TICKS ticks, regardless of absolute health. Catches "I'm being hit hard right
 *     now" even from a high starting HP -- added after a real death where health went
 *     18.5 -> 7.5 -> 0.0 in a handful of seconds; health was above FLEE_HEALTH_THRESHOLD right up
 *     until the fatal hit, so the absolute check alone never triggered. This trigger fires after
 *     the FIRST big hit (7.5, still "healthy" by the old check) instead of waiting for health to
 *     already be critical, giving flee an actual chance to react before a second big hit lands.
 *
 * Same always-on tick-check pattern as SocialGreetingController: own END_CLIENT_TICK hook, own
 * health tracking, own local-player input swap (bypasses BridgeInputController same as
 * SocialGreetingController -- that's for bridge-driven commands, not autonomous mod-side
 * reflexes).
 */
public final class AutoFleeController {

    private AutoFleeController() {}

    private static final float FLEE_HEALTH_THRESHOLD = 6.0f; // 3 hearts -- start fleeing
    private static final float SAFE_HEALTH_THRESHOLD = 10.0f; // 5 hearts -- stop fleeing (hysteresis)
    private static final double FLEE_RADIUS = 16.0; // wider than defend-on-attack's 8, react to threats a bit further out
    private static final float FLEE_TURN_RATE = 20f; // degrees/tick, between social-greeting's look rate and combat's 25

    // Burst-damage trigger: flee if health drops by more than BURST_DAMAGE_THRESHOLD within the
    // last WINDOW_TICKS ticks. 30 ticks = 1.5s, on the order of a real player's reaction window.
    // 7.0 HP comfortably catches a creeper blast or a volley of point-blank arrows (easily 8+ HP
    // in one hit) while not false-triggering on normal single-hit chip damage from weaker mobs.
    private static final int WINDOW_TICKS = 30;
    private static final float BURST_DAMAGE_THRESHOLD = 7.0f;
    private static final ArrayDeque<Float> healthHistory = new ArrayDeque<>(WINDOW_TICKS + 1);

    private static boolean fleeing;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(AutoFleeController::onTick);
        ArdorMasterToggle.register(AutoFleeController::cancel);
    }

    private static void onTick(Minecraft client) {
        try {
            tickInner(client);
        } catch (RuntimeException e) {
            System.err.println("[ardor] auto-flee failed: " + e);
            stopFleeing();
        }
    }

    /** Not polled every tick -- would re-stomp a human's manual input after re-enable. */
    public static void cancel() {
        stopFleeing();
    }

    private static void tickInner(Minecraft client) {
        if (!ArdorMasterToggle.isEnabled()) return;
        LocalPlayer self = client.player;
        if (self == null || client.level == null) {
            stopFleeing();
            healthHistory.clear();
            return;
        }

        float health = self.getHealth();
        boolean burstDamage = recordHealthAndCheckBurst(health);

        if (!fleeing) {
            // Either trigger can start fleeing: absolute-low-health (gradual attrition) or
            // burst-damage (a big hit just landed, regardless of how much health is left).
            if (health >= FLEE_HEALTH_THRESHOLD && !burstDamage) return;
            Entity threat = nearestHostile();
            if (threat == null) return;
            // Couldn't take control (e.g. an arrow dodge is still active) -- retry next tick.
            if (!startFleeing()) return;
        }

        if (health >= SAFE_HEALTH_THRESHOLD) {
            stopFleeing();
            return;
        }

        Entity threat = nearestHostile();
        if (threat == null) {
            stopFleeing();
            return;
        }

        runFleeTick(self, threat);
    }

    /**
     * Records health into the rolling window and reports whether health dropped by more than
     * BURST_DAMAGE_THRESHOLD over the last WINDOW_TICKS ticks. Only evaluates once the window is
     * actually full (WINDOW_TICKS readings old enough to compare against) -- avoids a false
     * trigger in the first couple seconds after the controller starts. A health INCREASE (heal,
     * respawn to full) just produces a negative/small diff, well under the positive damage
     * threshold, so this needs no separate respawn handling.
     */
    private static boolean recordHealthAndCheckBurst(float health) {
        boolean windowFull = healthHistory.size() == WINDOW_TICKS;
        float oldest = windowFull ? healthHistory.peekFirst() : 0f;
        healthHistory.addLast(health);
        if (healthHistory.size() > WINDOW_TICKS) healthHistory.removeFirst();
        return windowFull && (oldest - health) > BURST_DAMAGE_THRESHOLD;
    }

    private static Entity nearestHostile() {
        return SelectorResolver.resolveOne("@e[category=hostile,distance=" + FLEE_RADIUS + ",sort=nearest,limit=1]");
    }

    private static boolean startFleeing() {
        if (!InputSwapManager.tryAcquire(InputSwapManager.Owner.AUTO_FLEE)) return false;
        fleeing = true;
        GameActionController.stopAttacking(); // override the defend-on-attack reflex's attackUntilDead loop
        return true;
    }

    private static void runFleeTick(LocalPlayer self, Entity threat) {
        if (!InputSwapManager.isOwnedBy(InputSwapManager.Owner.AUTO_FLEE)) {
            // Pre-empted by a higher-priority controller (arrow dodge) -- stop cleanly.
            stopFleeing();
            return;
        }

        double dx = self.getX() - threat.getX();
        double dz = self.getZ() - threat.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0e-4) {
            dx = 1.0;
            dz = 0.0;
            dist = 1.0;
        }
        double awayX = self.getX() + dx / dist * 10.0;
        double awayZ = self.getZ() + dz / dist * 10.0;
        RotationUtil.smoothLookAt(self, awayX, self.getEyeY(), awayZ, FLEE_TURN_RATE);

        self.input.keyPresses = new Input(true, false, false, false, false, false, true);
        self.input.moveVector = new Vec2(0f, 1f);
    }

    private static void stopFleeing() {
        if (!fleeing) return;
        fleeing = false;
        InputSwapManager.release(InputSwapManager.Owner.AUTO_FLEE);
    }
}
