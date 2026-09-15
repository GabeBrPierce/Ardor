package com.ardor.game;

import com.ardor.client.ArdorMasterToggle;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * "I want to add a social protocol where we crouch rapidly jump up and down and move left and
 * right while facing players who are not attacking us." A lightweight greeting gesture, not an
 * LLM-planned task -- runs the same always-on tick-check pattern as the defend-on-attack reflex
 * (see RegionCombatController), but for the opposite situation: a peaceful nearby player instead
 * of a hostile mob.
 *
 * "Not attacking us" is approximated the same way the defend event has to (see
 * SelectorResolver's category=hostile javadoc): there's no real attacker-identity capture
 * anywhere in this mod, so this tracks the bot's OWN recent damage instead -- if health hasn't
 * dropped in the last DAMAGE_COOLDOWN_TICKS, whoever's nearby isn't currently fighting us.
 */
public final class SocialGreetingController {

    private SocialGreetingController() {}

    private static final double GREET_RANGE = 8.0;
    private static final int DAMAGE_COOLDOWN_TICKS = 60; // 3s -- must be damage-free this long before greeting
    private static final int GREETING_DURATION_TICKS = 60; // 3s greeting sequence
    private static final int GREETING_COOLDOWN_TICKS = 400; // 20s between greetings, any player

    private static float lastHealth = -1;
    private static int lastDamageTick = Integer.MIN_VALUE;
    private static int lastGreetingEndTick = Integer.MIN_VALUE;
    private static int tick;

    private static boolean greeting;
    private static int greetingTicksLeft;
    private static AbstractClientPlayer greetingTarget;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(SocialGreetingController::onTick);
        ArdorMasterToggle.register(SocialGreetingController::cancel);
    }

    public static void cancel() {
        stopGreeting();
    }

    private static void onTick(Minecraft client) {
        try {
            tickInner(client);
        } catch (RuntimeException e) {
            System.err.println("[ardor] social greeting failed: " + e);
            stopGreeting();
        }
    }

    private static void tickInner(Minecraft client) {
        tick++;
        if (!ArdorMasterToggle.isEnabled()) return;
        LocalPlayer self = client.player;
        if (self == null || client.level == null) {
            stopGreeting();
            return;
        }

        float health = self.getHealth();
        if (lastHealth >= 0 && health < lastHealth) lastDamageTick = tick;
        lastHealth = health;

        if (greeting) {
            runGreetingTick(self);
            return;
        }

        if (tick - lastDamageTick < DAMAGE_COOLDOWN_TICKS) return;
        if (tick - lastGreetingEndTick < GREETING_COOLDOWN_TICKS) return;

        AbstractClientPlayer nearest = nearestOtherPlayer(self);
        if (nearest != null) startGreeting(nearest);
    }

    private static AbstractClientPlayer nearestOtherPlayer(LocalPlayer self) {
        AbstractClientPlayer nearest = null;
        double nearestDistSqr = GREET_RANGE * GREET_RANGE;
        for (AbstractClientPlayer p : Minecraft.getInstance().level.players()) {
            if (p == self) continue;
            double d = p.distanceToSqr(self);
            if (d < nearestDistSqr) {
                nearest = p;
                nearestDistSqr = d;
            }
        }
        return nearest;
    }

    private static boolean startGreeting(AbstractClientPlayer target) {
        if (!InputSwapManager.tryAcquire(InputSwapManager.Owner.SOCIAL_GREETING)) return false;
        greeting = true;
        greetingTarget = target;
        greetingTicksLeft = GREETING_DURATION_TICKS;
        return true;
    }

    private static void runGreetingTick(LocalPlayer self) {
        if (!InputSwapManager.isOwnedBy(InputSwapManager.Owner.SOCIAL_GREETING)) {
            // Pre-empted by a higher-priority controller -- stop cleanly rather than keep writing.
            stopGreeting();
            return;
        }
        if (!greetingTarget.isAlive() || greetingTicksLeft-- <= 0) {
            stopGreeting();
            return;
        }
        RotationUtil.smoothLookAt(self, greetingTarget.getX(), greetingTarget.getEyeY(), greetingTarget.getZ(), 15f);

        boolean crouch = (greetingTicksLeft / 5) % 2 == 0;
        boolean jump = (greetingTicksLeft / 8) % 2 == 0;
        boolean left = (greetingTicksLeft / 10) % 2 == 0;
        boolean right = !left;
        self.input.keyPresses = new Input(false, false, left, right, jump, crouch, false);
        self.input.moveVector = new Vec2(left ? -1f : 1f, 0f).normalized();
    }

    private static void stopGreeting() {
        greeting = false;
        greetingTarget = null;
        lastGreetingEndTick = tick;
        InputSwapManager.release(InputSwapManager.Owner.SOCIAL_GREETING);
    }
}
