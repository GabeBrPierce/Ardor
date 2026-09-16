package com.ardor.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Chat-log status feedback for the voice/chat pipeline (listening, thinking, errors).
 *
 * "I like the [Ardor] text that pops up -- alongside the logs, not instead of them" -- this used
 * to be purely an on-screen action-bar message (Player.sendOverlayMessage), gone the instant it's
 * replaced and never written anywhere else, so a status the user actually SAW in-game (e.g. a
 * fetch/dig failure reason) left no trace for a later log-based diagnosis unless they happened to
 * describe it verbatim. Now every call prints to the log AND lands in the in-game chat log, where
 * it scrolls back.
 *
 * Deliberately no longer touches the action bar at all: that's a single slot the server also writes
 * to (ActionBar packets), so dozens of Ardor status lines a minute stomped whatever the server was
 * trying to show there. addClientSystemMessage is vanilla's own client-generated-message path --
 * purely local, no packet, and (unlike LocalPlayer.sendSystemMessage) not gated by the server's
 * canReceiveSystemMessages ability or logged as if the server had sent it.
 */
public final class StatusIndicator {

    private StatusIndicator() {}

    public static void show(String text) {
        System.out.println("[ardor] status: " + text);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.gui.getChat().addClientSystemMessage(Component.literal("[Ardor] " + text));
    }
}
