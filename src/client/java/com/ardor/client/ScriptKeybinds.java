package com.ardor.client;

import com.ardor.script.ScriptEngine;
import com.ardor.script.ScriptKeybindStore;
import com.ardor.script.ScriptStore;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * "It would also be great if we allowed the user to bind keys ... to these specific scripts."
 * Fabric/vanilla keybinds are a fixed registered set (not freely creatable at runtime per script),
 * so this registers a fixed pool of generic "Ardor Script Slot N" keybinds -- unbound by default,
 * bindable to any physical key via the normal Controls screen like any other keybind -- and
 * ScriptKeybindStore maps each slot number to a saved script name (set via //ardor keybind set,
 * see ScriptKeybindCommands). Pressing a bound slot's key runs whatever script it's mapped to, if
 * any; an unmapped slot's press is a silent no-op.
 */
public final class ScriptKeybinds {

    public static final int SLOT_COUNT = 6;
    private static final KeyMapping[] KEYS = new KeyMapping[SLOT_COUNT];

    static {
        for (int i = 0; i < SLOT_COUNT; i++) {
            int slot = i + 1;
            KEYS[i] = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                    "key.ardor.scriptslot" + slot, InputConstants.UNKNOWN.getValue(), ArdorKeyCategory.ARDOR));
        }
    }

    private ScriptKeybinds() {}

    /** The real KeyMapping for a slot (1..SLOT_COUNT) -- for reading its currently bound physical key, e.g. via getTranslatedKeyMessage(). */
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
        String scriptName = ScriptKeybindStore.load().get(slot);
        if (scriptName == null) return;
        try {
            String source = ScriptStore.load(scriptName);
            ScriptEngine.run(source, scriptName, error ->
                    Minecraft.getInstance().execute(() -> StatusIndicator.show("Script slot " + slot + " ('" + scriptName + "') failed: " + error)));
        } catch (RuntimeException e) {
            StatusIndicator.show("Script slot " + slot + " -> '" + scriptName + "' failed to load: " + e.getMessage());
        }
    }
}
