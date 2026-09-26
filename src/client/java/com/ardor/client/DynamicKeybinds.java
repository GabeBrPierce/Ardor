package com.ardor.client;

import com.ardor.game.PathfindingController;
import com.ardor.script.ScriptEngine;
import com.ardor.script.ScriptStore;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * Triggers KeybindStore entries against raw physical key state -- there's no real KeyMapping behind
 * any of these (see KeybindEntry/KeybindStore for why), so this polls InputConstants.isKeyDown for
 * every key in each entry's combo every client tick, same as KeybindTicker's consumeClick() dance
 * but hand-rolled since consumeClick() only exists on a real KeyMapping.
 *
 * Fires once on the rising edge (every key down THIS tick, not already all-down last tick) so a
 * held chord runs its script/macro exactly once per press, not every tick it's held -- same "tap,
 * not hold-repeat" feel every other keybind in this mod already has. Skipped entirely while any
 * screen is open, same "a screen owns input entirely once open" reasoning PickWheelKey's own tap-
 * fallback bug fix established -- this prevents e.g. typing a bound letter into KeybindsScreen's own
 * search box, or ScriptEditScreen, from also firing that letter's keybind.
 */
public final class DynamicKeybinds {

    private static boolean registered;

    private DynamicKeybinds() {}

    public static void register() {
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick(client);
            } catch (RuntimeException e) {
                System.err.println("[ardor] dynamic keybinds tick failed: " + e);
            }
        });
    }

    private static void tick(Minecraft client) {
        if (client.gui.screen() != null) return;
        for (KeybindEntry entry : KeybindStore.load()) {
            boolean down = !entry.keys.isEmpty() && allDown(entry);
            if (down && !entry.wasDownLastTick) run(entry);
            entry.wasDownLastTick = down;
        }
    }

    private static boolean allDown(KeybindEntry entry) {
        for (int key : entry.keys) {
            if (!InputConstants.isKeyDown(key)) return false;
        }
        return true;
    }

    private static void run(KeybindEntry entry) {
        if (entry.name.isEmpty()) return;
        if ("macro".equals(entry.kind)) {
            JsonObject action = new JsonObject();
            action.addProperty("action", "macro");
            action.addProperty("name", entry.name);
            try {
                PathfindingController.dispatch(action);
            } catch (RuntimeException e) {
                StatusIndicator.show("Keybind macro '" + entry.name + "' failed: " + e.getMessage());
            }
            return;
        }
        try {
            String source = ScriptStore.load(entry.name);
            ScriptEngine.run(source, entry.name, error ->
                    Minecraft.getInstance().execute(() -> StatusIndicator.show("Keybind script '" + entry.name + "' failed: " + error)));
        } catch (RuntimeException e) {
            StatusIndicator.show("Keybind script '" + entry.name + "' failed to load: " + e.getMessage());
        }
    }
}
