package com.ardor.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    private static final Map<String, String> LAST_SHOWN = new ConcurrentHashMap<>();

    /**
     * Same as show(), but only actually shows if `text` differs from the last thing shown under
     * `key` -- for an error that can recur every tick/poll cycle forever (an event predicate that
     * keeps failing the same way), so it reads once in chat instead of spamming a new line every
     * cycle. A DIFFERENT message under the same key (or the same message after something else
     * cleared the key) shows again.
     */
    public static void showOnce(String key, String text) {
        if (!text.equals(LAST_SHOWN.put(key, text))) show(text);
    }

    /** Lets a since-fixed error under `key` show again if it recurs, instead of staying silenced by showOnce's own memory of it. */
    public static void clearOnce(String key) {
        LAST_SHOWN.remove(key);
    }
}
