package com.ardor.history;

import com.google.gson.JsonObject;
import com.ardor.ir.EnglishActionRenderer;

import java.time.Instant;

/** Logs every dispatched action: raw IR (training data) plus its English rendering (human review). */
public final class ActionHistory extends SessionRecorder {

    private static final ActionHistory INSTANCE = new ActionHistory();

    private ActionHistory() {
        super("actions");
    }

    public static void log(JsonObject action, String source) {
        JsonObject entry = new JsonObject();
        entry.addProperty("ts", Instant.now().toString());
        entry.addProperty("source", source);
        entry.add("action", action);
        String english = tryRender(action);
        if (english != null) entry.addProperty("english", english);
        INSTANCE.append(entry);
    }

    private static String tryRender(JsonObject action) {
        try {
            return EnglishActionRenderer.render(action);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
