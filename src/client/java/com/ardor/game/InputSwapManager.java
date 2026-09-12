package com.ardor.game;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;

/**
 * Single arbitration point for the player.input swap technique that
 * BridgeInputController, SocialGreetingController, AutoFleeController, and
 * ArrowDodgeController all use to drive the player programmatically.
 *
 * Previously each of those four had its own private originalInput/inputSwapped
 * pair and did the swap/restore independently -- correct only if they always
 * nested in strict LIFO order, and actively wrong the instant two were active
 * in the same tick (both writing player.input fields directly, last tick
 * listener to run wins) or if one was pre-empted and never got to restore
 * (leaving player.input stuck on a synthetic instance). This class holds that
 * state ONCE, with a single owner slot (not a stack -- only one controller
 * should ever be driving the player at a time) and a priority order so a
 * higher-priority controller can pre-empt a lower one instead of fighting it.
 *
 * Priority order (highest first), and why:
 *   ARROW_DODGE      - very short (6-14 tick) survival reflex; once it starts
 *                       it should be allowed to finish uninterrupted, so
 *                       nothing pre-empts it.
 *   PARRY            - melee block/counter-attack cycle; damage is as
 *                       imminent as an inbound arrow but ARROW_DODGE is
 *                       deliberately short-lived and meant to finish
 *                       uninterrupted, so PARRY ranks just below it. Ranked
 *                       above DROWNING/AUTO_FLEE so a health-critical retreat
 *                       or a rising drowning risk can still pre-empt an
 *                       in-progress parry.
 *   DROWNING         - environmental hazard AUTO_FLEE structurally can't
 *                       cover (it only engages against a nearby hostile
 *                       entity; drowning has none). Fires with real buffer
 *                       (~5s of air left), not an imminent impact like
 *                       ARROW_DODGE/PARRY, so it ranks below both -- but
 *                       above AUTO_FLEE, since "swim up" and "run from a mob"
 *                       are different physical actions and a live test
 *                       confirmed AUTO_FLEE's own health thresholds arrive
 *                       too late (or not at all, absent a hostile) to cover
 *                       a slow drown on their own.
 *   AUTO_FLEE        - survival-critical but longer-running (until health
 *                       recovers or the threat is gone); loses to an
 *                       in-progress dodge, parry, or drowning response but
 *                       pre-empts anything else.
 *   BRIDGE           - explicit human/companion intent (input.set). Ranked
 *                       above autonomous cosmetic behavior, but below the
 *                       autonomous safety reflexes above -- an explicit
 *                       "walk forward" should not fight an active dodge/flee.
 *   SOCIAL_GREETING  - purely cosmetic; backs off for anything else that
 *                       wants control.
 */
public final class InputSwapManager {

    private InputSwapManager() {}

    public enum Owner {
        ARROW_DODGE(5),
        PARRY(4),
        DROWNING(3),
        AUTO_FLEE(2),
        BRIDGE(1),
        SOCIAL_GREETING(0);

        final int priority;

        Owner(int priority) {
            this.priority = priority;
        }
    }

    private static Owner currentOwner;
    private static ClientInput originalInput;

    /**
     * Attempts to take over player.input for the given owner. Returns true if
     * the caller may now write to player.input (either nobody currently owns
     * it, the caller already owns it, or the caller out-ranks the current
     * owner). Only captures the true original input when going from "no
     * owner" to "someone owns it" -- pre-empting an existing owner leaves the
     * already-synthetic player.input in place and just changes who may write
     * to it.
     */
    public static boolean tryAcquire(Owner requester) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return false;

        if (currentOwner == null) {
            originalInput = player.input;
            player.input = new ClientInput();
            currentOwner = requester;
            return true;
        }
        if (currentOwner == requester) return true;
        if (requester.priority > currentOwner.priority) {
            currentOwner = requester;
            return true;
        }
        return false;
    }

    /** Whether the given owner currently holds control -- checked each tick by a controller already mid-action, so it notices a pre-emption instead of blindly continuing to write. */
    public static boolean isOwnedBy(Owner owner) {
        return currentOwner == owner;
    }

    /**
     * Relinquishes control. Only actually restores the true original
     * player.input if `owner` is the current owner -- a stale/late release
     * from a controller that already got pre-empted is a no-op, so it can't
     * clobber whoever pre-empted it.
     */
    public static void release(Owner owner) {
        if (currentOwner != owner) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) player.input = originalInput;
        originalInput = null;
        currentOwner = null;
    }
}
