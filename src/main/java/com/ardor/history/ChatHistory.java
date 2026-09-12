package com.ardor.history;

import com.google.gson.JsonObject;

import java.time.Instant;

/**
 * Logs every observed chat message (and every bot response) -- raw material
 * for a future addressee-detection classifier. Logs everything, not just
 * triggered messages, so most entries are negative examples ("not directed
 * at the bot") by construction -- exactly the labeled data that kind of
 * classifier needs.
 */
public final class ChatHistory extends SessionRecorder {

    private static final ChatHistory INSTANCE = new ChatHistory();

    private ChatHistory() {
        super("chat");
    }

    public static void logChat(String sender, String message, boolean triggeredBot) {
        INSTANCE.append(entry("chat", sender, message, triggeredBot));
    }

    public static void logBotResponse(String sayText) {
        INSTANCE.append(entry("bot_say", "ardor", sayText, true));
    }

    private static JsonObject entry(String type, String sender, String message, boolean triggeredBot) {
        JsonObject o = new JsonObject();
        o.addProperty("ts", Instant.now().toString());
        o.addProperty("type", type);
        o.addProperty("sender", sender);
        o.addProperty("message", message);
        o.addProperty("triggeredBot", triggeredBot);
        return o;
    }
}
