package com.ardor.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Action-bar status feedback for the voice/chat pipeline (listening, thinking, errors).
 *
 * "I like the [Ardor] text that pops up -- alongside the logs, not instead of them" -- this used
 * to be purely an on-screen action-bar message (Player.sendOverlayMessage), gone the instant it's
 * replaced and never written anywhere else, so a status the user actually SAW in-game (e.g. a
 * fetch/dig failure reason) left no trace for a later log-based diagnosis unless they happened to
 * describe it verbatim. Now every call also prints to the log with the exact same text, so it's
 * both visible live in-game (unchanged) and grep-able afterward without relying on the user to
 * transcribe what flashed by.
 */
public final class StatusIndicator {

    private StatusIndicator() {}

    public static void show(String text) {
        System.out.println("[ardor] status: " + text);
        var player = Minecraft.getInstance().player;
        if (player != null) player.sendOverlayMessage(Component.literal("[Ardor] " + text));
    }
}
