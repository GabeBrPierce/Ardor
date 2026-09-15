package com.ardor.game;

import com.ardor.client.ArdorMasterToggle;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * Safety-net reflex for drowning/suffocation, independent of whatever else is going on --
 * exactly the gap flagged in TODO.md after a live test drowned the character over ~163 seconds
 * while stuck floating in a shallow pond running an unrelated action (`hole`). Nothing in the
 * codebase ever noticed or reacted to the rising drowning risk: AutoFleeController's health
 * thresholds only fire once damage has already started, and separately investigated to be
 * structurally unable to help here in general -- it requires a nearby hostile entity to engage
 * at all, and even where its health-threshold logic could apply, "swim up" and "run away from a
 * mob" are different physical actions, so a generic mob-flee response is the wrong shape for a
 * drowning response regardless. This is a dedicated, narrow reflex for that one condition.
 *
 * Same always-on END_CLIENT_TICK / try-catch-never-crash / InputSwapManager-arbitrated shape as
 * AutoFleeController/ArrowDodgeController/ParryController.
 *
 * API notes (all verified via javap against the real 26.1.2 client jar, not assumed from stock-
 * Minecraft memory, per this codebase's established discipline -- see SleepController's
 * Level.isDarkOutside() precedent for why that discipline matters in this build):
 *  - getAirSupply()/getMaxAirSupply()/setAirSupply(int) and isEyeInFluid(TagKey&lt;Fluid&gt;) all
 *    exist under their vanilla names -- but on Entity, not LivingEntity where vanilla tradition
 *    would suggest looking first. Confirmed by javap -p on both classes; LivingEntity only has
 *    the protected increase/decreaseAirSupply helpers, the public accessors live one level up.
 *    Doesn't change how LocalPlayer callers use them (LocalPlayer extends Entity either way), but
 *    is exactly the kind of quiet restructuring this build is known for, so it was checked rather
 *    than assumed.
 *  - Entity.TOTAL_AIR_SUPPLY = 300 (confirmed via javap -constants) -- matches vanilla's 15-second
 *    full air bar, unchanged in this build.
 *  - FluidTags.WATER exists unchanged; isEyeInFluid(FluidTags.WATER) is used directly, exactly
 *    the API this task named as the expected candidate.
 *  - The swim-up mechanic (Input.jump() while eye-in-fluid) was confirmed by disassembling
 *    LivingEntity.aiStep(), not assumed from vanilla habit: the jump-in-liquid branch is gated on
 *    the `jumping` field (set from player input) AND isAffectedByFluids() AND isInWater() with a
 *    positive fluid height at the entity's position, and calls jumpInLiquid(FluidTags.WATER),
 *    which (per its own disassembly) unconditionally adds +0.04 to vertical velocity for that
 *    tick. Holding jump every tick while submerged is therefore real, continuous upward thrust,
 *    not a one-shot hop -- exactly what a sustained swim-to-surface action needs.
 *
 * Trigger/release thresholds and why no randomness is used: unlike GameActionController's attack
 * interval or ParryController's block-phase sampling -- both tuned to *look* human by avoiding a
 * robotic metronome -- this is a hard safety cutoff, not a timing choice anyone is watching for
 * "naturalness". There's nothing to disguise: the player is either dangerously low on air or not,
 * and reacting on a fixed tick count the instant that's true is strictly better than adding
 * jitter that could, in the unlucky case, delay the reaction. So this deliberately does NOT
 * borrow the Gaussian-sampling pattern from ParryController/GameActionController.
 *
 *  - TRIGGER_AIR_TICKS (100, ~5s remaining out of the 300-tick/15s bar): starts the reflex with
 *    real time to spare before air hits zero and drowning damage begins, rather than waiting
 *    until the last moment -- same "give the reflex an actual chance to work" reasoning
 *    AutoFleeController's burst-damage trigger was built around.
 *  - SAFE_AIR_TICKS (250, 5/6 of the bar): release threshold, well above TRIGGER_AIR_TICKS so the
 *    reflex doesn't flicker on/off right at the boundary -- same hysteresis-on-release shape as
 *    AutoFleeController's FLEE_HEALTH_THRESHOLD/SAFE_HEALTH_THRESHOLD gap. Also released
 *    immediately, independent of air value, the instant the eye is no longer in fluid at all
 *    (truly surfaced) -- there's no reason to keep holding jump once air has stopped draining.
 *  - These are absolute tick counts against the vanilla default max (Respiration-boosted max air
 *    isn't specially handled) -- same "absolute value, not a fraction of a variable max" style
 *    AutoFleeController already uses for health thresholds.
 */
public final class DrowningSafetyController {

    private DrowningSafetyController() {}

    private static final int TRIGGER_AIR_TICKS = 100; // ~5s of air left -- start swimming up
    private static final int SAFE_AIR_TICKS = 250; // ~12.5s of air recovered -- stop (hysteresis)

    private static boolean swimming;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(DrowningSafetyController::onTick);
        ArdorMasterToggle.register(DrowningSafetyController::cancel);
    }

    public static void cancel() {
        stopSwimming();
    }

    private static void onTick(Minecraft client) {
        try {
            tickInner(client);
        } catch (RuntimeException e) {
            System.err.println("[ardor] drowning safety failed: " + e);
            stopSwimming();
        }
    }

    private static void tickInner(Minecraft client) {
        if (!ArdorMasterToggle.isEnabled()) return;
        LocalPlayer self = client.player;
        if (self == null || client.level == null) {
            stopSwimming();
            return;
        }

        if (!swimming) {
            if (!self.isEyeInFluid(FluidTags.WATER)) return;
            if (self.getAirSupply() > TRIGGER_AIR_TICKS) return;
            // Couldn't take control (a higher-priority reflex is active) -- retry next tick.
            if (!startSwimming()) return;
        }

        if (!InputSwapManager.isOwnedBy(InputSwapManager.Owner.DROWNING)) {
            // Pre-empted by a higher-priority controller -- stop cleanly.
            stopSwimming();
            return;
        }

        if (!self.isEyeInFluid(FluidTags.WATER) || self.getAirSupply() >= SAFE_AIR_TICKS) {
            stopSwimming();
            return;
        }

        applySwimUpInput(self);
    }

    private static boolean startSwimming() {
        if (!InputSwapManager.tryAcquire(InputSwapManager.Owner.DROWNING)) return false;
        swimming = true;
        return true;
    }

    private static void applySwimUpInput(LocalPlayer self) {
        // Jump only, zero horizontal movement -- the goal is straight up to air, and adding
        // forward risks swimming further under an overhang instead of toward the surface.
        self.input.keyPresses = new Input(false, false, false, false, true, false, false);
        self.input.moveVector = Vec2.ZERO;
    }

    private static void stopSwimming() {
        if (!swimming) return;
        swimming = false;
        InputSwapManager.release(InputSwapManager.Owner.DROWNING);
    }
}
