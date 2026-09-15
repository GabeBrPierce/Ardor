package com.ardor.bridge;

import com.ardor.client.ArdorMasterToggle;
import com.ardor.game.InputSwapManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * Phase 0 of the mod/companion split (see the approved plan): the mod's
 * lowest-level movement primitive, the `input.set` bridge command. Holds
 * whatever key state the companion app last sent -- a full replace each
 * time, not a patch, matching "held key" semantics -- and ticks it into the
 * player every frame via the exact input-swap technique PathExecutor/
 * MacroPlayer already use and this session already verified: swap
 * player.input to an inert ClientInput() so nothing (real keyboard
 * included) fights it, restore the real input the moment every key goes
 * false again.
 */
public final class BridgeInputController {

    private BridgeInputController() {}

    private static volatile boolean forward, back, left, right, jump, sneak, sprint;

    private static boolean registered;

    public static void register() {
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(BridgeInputController::tick);
        ArdorMasterToggle.register(BridgeInputController::cancel);
    }

    public static void setInput(boolean forward, boolean back, boolean left, boolean right,
                                 boolean jump, boolean sneak, boolean sprint) {
        BridgeInputController.forward = forward;
        BridgeInputController.back = back;
        BridgeInputController.left = left;
        BridgeInputController.right = right;
        BridgeInputController.jump = jump;
        BridgeInputController.sneak = sneak;
        BridgeInputController.sprint = sprint;
    }

    private static boolean anyHeld() {
        return forward || back || left || right || jump || sneak || sprint;
    }

    /** Drops held keys and releases input ownership so nothing stays stuck applied after disable. */
    public static void cancel() {
        forward = back = left = right = jump = sneak = sprint = false;
        InputSwapManager.release(InputSwapManager.Owner.BRIDGE);
    }

    // An uncaught exception here crashes the whole client -- confirmed live
    // elsewhere this session (TaskRunner/PushToTalk/MacroPlayer/etc.), same guard.
    private static void tick(Minecraft client) {
        try {
            tickInner(client);
        } catch (RuntimeException e) {
            System.err.println("[ardor] bridge input tick failed: " + e);
        }
    }

    private static void tickInner(Minecraft client) {
        if (!ArdorMasterToggle.isEnabled()) return;
        LocalPlayer player = client.player;
        if (player == null || !anyHeld()) {
            InputSwapManager.release(InputSwapManager.Owner.BRIDGE);
            return;
        }
        if (!InputSwapManager.tryAcquire(InputSwapManager.Owner.BRIDGE)) return;
        player.input.keyPresses = new Input(forward, back, left, right, jump, sneak, sprint);
        float forwardImpulse = forward != back ? (forward ? 1f : -1f) : 0f;
        float strafeImpulse = left != right ? (left ? 1f : -1f) : 0f;
        player.input.moveVector = new Vec2(strafeImpulse, forwardImpulse).normalized();
    }
}
