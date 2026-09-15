package com.ardor.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

/** Panic-stop keybind -- see PanicStop's own doc for the incident this exists for. Deliberately NOT gated on client.screen == null (unlike every other Ardor keybind): stopping the bot has to work even while a screen happens to be open. Bound to K by default. */
public final class PanicStopKey {

    private static final KeyMapping KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.ardor.panicstop", InputConstants.KEY_K, ArdorKeyCategory.ARDOR));

    private PanicStopKey() {}

    public static void register() {
        KeybindTicker.add(KEY, PanicStop::now);
    }
}
