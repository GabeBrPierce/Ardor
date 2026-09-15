package com.ardor.client;

import com.ardor.macro.MacroRecorder;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

/**
 * Unbound by default. Starting a macro recording needs a typed name, so that only happens from
 * MacroListScreen -- but stopping doesn't need one (MacroRecorder already knows what it's
 * recording), so this keybind lets a recording end without reopening the menu while moving around.
 * No-op if nothing is currently recording.
 */
public final class MacroStopRecordingKey {

    private static final KeyMapping KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.ardor.macrostoprecording", InputConstants.UNKNOWN.getValue(), ArdorKeyCategory.ARDOR));

    private MacroStopRecordingKey() {}

    public static void register() {
        KeybindTicker.add(KEY, () -> {
            if (MacroRecorder.isRecording()) MacroRecorder.stop();
        });
    }
}
