package com.ardor.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Action-bar status feedback for the voice/chat pipeline (listening, thinking, errors). */
public final class StatusIndicator {

    private StatusIndicator() {}

    public static void show(String text) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.sendOverlayMessage(Component.literal("[Ardor] " + text));
    }
}
