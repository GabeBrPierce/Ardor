package com.ardor.macro;

import com.ardor.client.StatusIndicator;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * "I want to be able to record movements and save them as well as their own
 * named macros." Records the PLAYER'S OWN real input each tick (keyPresses +
 * yaw/pitch) while active -- started/stopped via //ardor record start/stop
 * (MacroCommands), not a held key, since a macro can run for a while and
 * "hold a key the whole time" doesn't fit that. Captures whatever the real
 * KeyboardInput produced that tick, so this only makes sense to run while
 * NOT also driving the bot programmatically (recording while PathExecutor
 * has swapped player.input to its own inert ClientInput would just record
 * silence -- not guarded against, since doing both at once isn't a
 * meaningful thing to do anyway).
 */
public final class MacroRecorder {

    private static boolean recording;
    private static Macro current;
    private static boolean registered;

    private MacroRecorder() {}

    public static boolean isRecording() {
        return recording;
    }

    public static void start(String name) {
        current = new Macro();
        current.name = name;
        recording = true;
        ensureRegistered();
        StatusIndicator.show("Recording macro '" + name + "'...");
    }

    public static void stop() {
        if (!recording) return;
        recording = false;
        MacroStore.save(current);
        StatusIndicator.show("Saved macro '" + current.name + "' (" + current.samples.size() + " ticks)");
        current = null;
    }

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick(client);
            } catch (RuntimeException e) {
                System.err.println("[ardor] macro recorder tick failed, stopping: " + e);
                recording = false;
            }
        });
    }

    private static void tick(Minecraft client) {
        if (!recording) return;
        LocalPlayer player = client.player;
        if (player == null) return;

        Macro.Sample s = new Macro.Sample();
        s.forward = player.input.keyPresses.forward();
        s.backward = player.input.keyPresses.backward();
        s.left = player.input.keyPresses.left();
        s.right = player.input.keyPresses.right();
        s.jump = player.input.keyPresses.jump();
        s.shift = player.input.keyPresses.shift();
        s.sprint = player.input.keyPresses.sprint();
        s.yaw = player.getYRot();
        s.pitch = player.getXRot();
        current.samples.add(s);
    }
}
