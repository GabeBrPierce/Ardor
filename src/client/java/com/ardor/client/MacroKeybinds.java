package com.ardor.client;

import com.ardor.game.PathfindingController;
import com.ardor.macro.MacroKeybindStore;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

/**
 * Fixed pool of generic "Ardor Macro Slot N" keybinds, unbound by default -- same shape as
 * ScriptKeybinds, for the same reason (Fabric/vanilla keybinds are a fixed registered set, not
 * freely creatable at runtime). MacroKeybindStore maps each slot to a saved macro name; pressing a
 * bound slot's key plays that macro via the same PathfindingController.dispatch({"action":"macro"})
 * path every other macro playback (Lua's runMacro, the "macro" IR verb) already goes through.
 */
public final class MacroKeybinds {

    public static final int SLOT_COUNT = 6;
    private static final KeyMapping[] KEYS = new KeyMapping[SLOT_COUNT];

    static {
        for (int i = 0; i < SLOT_COUNT; i++) {
            int slot = i + 1;
            KEYS[i] = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                    "key.ardor.macroslot" + slot, InputConstants.UNKNOWN.getValue(), ArdorKeyCategory.ARDOR));
        }
    }

    private MacroKeybinds() {}

    /** The real KeyMapping for a slot (1..SLOT_COUNT). */
    public static KeyMapping key(int slot) {
        return KEYS[slot - 1];
    }

    public static void register() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            int slot = i + 1;
            KeybindTicker.add(KEYS[i], () -> runSlot(slot));
        }
    }

    private static void runSlot(int slot) {
        String macroName = MacroKeybindStore.load().get(slot);
        if (macroName == null) return;
        JsonObject action = new JsonObject();
        action.addProperty("action", "macro");
        action.addProperty("name", macroName);
        try {
            PathfindingController.dispatch(action);
        } catch (RuntimeException e) {
            StatusIndicator.show("Macro slot " + slot + " -> '" + macroName + "' failed: " + e.getMessage());
        }
    }
}
