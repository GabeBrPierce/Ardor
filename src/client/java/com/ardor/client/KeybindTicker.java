package com.ardor.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared END_CLIENT_TICK dispatcher for simple consumeClick()-driven keybinds, so each keybind
 * class doesn't register its own separate tick listener. Each registered action runs inside its
 * own try/catch, same as before consolidation, so one misbehaving action can't break another.
 */
public final class KeybindTicker {

    private interface Entry {
        void tick();
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static boolean registered;

    private KeybindTicker() {}

    public static void add(KeyMapping key, Runnable action) {
        ensureRegistered();
        ENTRIES.add(() -> {
            while (key.consumeClick()) {
                action.run();
            }
        });
    }

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            for (Entry entry : ENTRIES) {
                try {
                    entry.tick();
                } catch (RuntimeException e) {
                    System.err.println("[ardor] keybind tick failed: " + e);
                }
            }
        });
    }
}
