package com.ardor.container;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-COMMAND-source cooldown tracking, in-memory only -- deliberately NOT persisted, mirroring
 * ContainerCache's own documented reasoning for why scanned contents aren't written to disk: a
 * cooldown timer that survived a client restart would misrepresent a guess (we don't actually know
 * the server's real cooldown clock, just what we last sent and the user-entered duration) as
 * durable data. ContainerSource.cooldownSeconds is the user-entered duration; this class just
 * tracks "when did we last actually send this source's command."
 */
public final class CommandCooldowns {

    private CommandCooldowns() {}

    private static final Map<String, Long> READY_AT_MILLIS = new HashMap<>();

    public static boolean isOnCooldown(String sourceId) {
        return remainingSeconds(sourceId) > 0;
    }

    public static int remainingSeconds(String sourceId) {
        Long readyAt = READY_AT_MILLIS.get(sourceId);
        if (readyAt == null) return 0;
        long remainingMillis = readyAt - System.currentTimeMillis();
        return remainingMillis <= 0 ? 0 : (int) Math.ceil(remainingMillis / 1000.0);
    }

    public static void markUsed(String sourceId, Integer cooldownSeconds) {
        if (cooldownSeconds == null || cooldownSeconds <= 0) return;
        READY_AT_MILLIS.put(sourceId, System.currentTimeMillis() + cooldownSeconds * 1000L);
    }
}
