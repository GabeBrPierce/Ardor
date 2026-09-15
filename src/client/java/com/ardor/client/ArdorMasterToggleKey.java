package com.ardor.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

/** Keybind for ArdorMasterToggle. Bound to O by default (H/K/P/V/J/B already taken -- see FetchItemsKey/PanicStopKey/PauseToggleKey/PushToTalk/ScriptWheelKey/TaskPlannerKey). */
public final class ArdorMasterToggleKey {

    private static final KeyMapping KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.ardor.mastertoggle", InputConstants.KEY_O, ArdorKeyCategory.ARDOR));

    private ArdorMasterToggleKey() {}

    public static void register() {
        KeybindTicker.add(KEY, ArdorMasterToggle::toggle);
    }
}
