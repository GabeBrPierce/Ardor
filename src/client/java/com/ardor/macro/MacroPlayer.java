package com.ardor.macro;

import com.ardor.client.StatusIndicator;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * "I want the AI to have access to these and be able to run them" -- plays
 * a recorded Macro back at 1 sample/tick (matching the 20tps rate it was
 * recorded at), driving the player exactly the same way PathExecutor does:
 * swap player.input to an inert ClientInput() for the duration (so nothing
 * -- real keyboard or otherwise -- fights the replay), set keyPresses AND
 * moveVector every tick (moveVector recomputed from the recorded
 * forward/backward/left/right the same way KeyboardInput.tick() derives it,
 * since only keyPresses was actually recorded -- see MacroRecorder), restore
 * real input the moment playback ends.
 *
 * A straight input replay, not a position replay -- if the world has
 * changed since recording (a block moved, mobs in a different spot), the
 * result can diverge from the original run. No attempt made to detect or
 * correct for that.
 */
public final class MacroPlayer {

    private Macro macro;
    private int index;
    private boolean active;
    private Runnable onDone;
    private boolean registered;

    private ClientInput originalInput;
    private boolean inputSwapped;

    public boolean isActive() {
        return active;
    }

    public void play(String name, Runnable onDone) {
        this.macro = MacroStore.load(name);
        this.index = 0;
        this.onDone = onDone;
        this.active = true;
        ensureRegistered();
        StatusIndicator.show("Playing macro '" + name + "' (" + macro.samples.size() + " ticks)...");
    }

    public void cancel() {
        active = false;
        clearInput();
        restoreInput();
    }

    private void ensureRegistered() {
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    // An uncaught exception here crashes the whole client -- confirmed live,
    // see TODO.md and PushToTalk's tick handler. Never let one escape.
    private void tick(Minecraft client) {
        try {
            tickInner(client);
        } catch (RuntimeException e) {
            System.err.println("[ardor] macro player tick failed, cancelling: " + e);
            active = false;
            clearInput();
            restoreInput();
        }
    }

    private void tickInner(Minecraft client) {
        LocalPlayer player = client.player;
        if (!active || player == null) return;

        if (index >= macro.samples.size()) {
            active = false;
            clearInput();
            restoreInput();
            Runnable done = onDone;
            if (done != null) done.run();
            return;
        }

        ensureInputSwapped(player);
        Macro.Sample s = macro.samples.get(index++);

        player.setYRot(s.yaw);
        player.setXRot(s.pitch);
        player.setYHeadRot(s.yaw);
        player.input.keyPresses = new Input(s.forward, s.backward, s.left, s.right, s.jump, s.shift, s.sprint);

        float forwardImpulse = s.forward != s.backward ? (s.forward ? 1f : -1f) : 0f;
        float strafeImpulse = s.left != s.right ? (s.left ? 1f : -1f) : 0f;
        player.input.moveVector = new Vec2(strafeImpulse, forwardImpulse).normalized();
    }

    private void ensureInputSwapped(LocalPlayer player) {
        if (inputSwapped) return;
        originalInput = player.input;
        player.input = new ClientInput();
        inputSwapped = true;
    }

    private void restoreInput() {
        if (!inputSwapped) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) player.input = originalInput;
        originalInput = null;
        inputSwapped = false;
    }

    private void clearInput() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.input.keyPresses = new Input(false, false, false, false, false, false, false);
            player.input.moveVector = Vec2.ZERO;
        }
    }
}
