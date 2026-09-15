package com.ardor.client;

import com.ardor.voice.VoicePipeline;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

public final class PushToTalk {

    private static final KeyMapping KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.ardor.talk", InputConstants.KEY_V, ArdorKeyCategory.ARDOR));

    private static final VoicePipeline PIPELINE = new VoicePipeline();
    private static boolean wasDown = false;

    private PushToTalk() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean isDown = KEY.isDown();
            if (isDown && !wasDown) onPress();
            if (!isDown && wasDown) onRelease();
            wasDown = isDown;
        });
    }

    // Both wrapped: this runs inside a Fabric tick callback, and an uncaught
    // exception there crashes the whole client (confirmed -- see TODO.md).
    // Nothing the voice pipeline does is allowed to take the game down with it.

    private static void onPress() {
        try {
            StatusIndicator.show("Listening...");
            PIPELINE.startListening();
        } catch (RuntimeException e) {
            System.err.println("[ardor] onPress failed: " + e);
            StatusIndicator.show("Error: " + e.getMessage());
        }
    }

    private static void onRelease() {
        try {
            StatusIndicator.show("Thinking...");
            PIPELINE.stopListeningAndRespond();
        } catch (RuntimeException e) {
            System.err.println("[ardor] onRelease failed: " + e);
            StatusIndicator.show("Error: " + e.getMessage());
        }
    }
}
