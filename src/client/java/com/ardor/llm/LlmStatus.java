package com.ardor.llm;

import com.ardor.config.ArdorConfig;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cached, UI-facing view of whether config.llmBaseUrl is currently reachable
 * -- backs the status line on TaskPlannerScreen ("B screen"). A real network
 * probe (LlmServerManager.isServerUp) runs async on refresh(); current()
 * just reads whatever was last observed, so rendering never blocks on the
 * network. Callers should call refresh() periodically (TaskPlannerScreen
 * does so on init() and every ~2s via tick()) rather than on every frame.
 */
public final class LlmStatus {

    public enum State { UNKNOWN, CHECKING, CONNECTED, DISCONNECTED, STARTING }

    private static volatile State state = State.UNKNOWN;
    private static final AtomicBoolean checking = new AtomicBoolean(false);

    private LlmStatus() {}

    public static State current() {
        return state;
    }

    static void markConnected() {
        state = State.CONNECTED;
    }

    static void markDisconnected() {
        state = State.DISCONNECTED;
    }

    static void markStarting() {
        state = State.STARTING;
    }

    /** Kicks off an async reachability check if one isn't already in flight; safe to call often (e.g. every screen tick). */
    public static void refresh() {
        if (!checking.compareAndSet(false, true)) return;
        CompletableFuture.supplyAsync(() -> LlmServerManager.isServerUp(ArdorConfig.get().llmBaseUrl, 2000))
                .thenAccept(up -> {
                    state = up ? State.CONNECTED : State.DISCONNECTED;
                    checking.set(false);
                })
                .exceptionally(err -> {
                    state = State.DISCONNECTED;
                    checking.set(false);
                    return null;
                });
    }
}
