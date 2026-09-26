package com.ardor.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

/** Cycles CameraModeController's NORMAL -> SIMS -> FLY -> NORMAL. Bound to F6 by default (all of Ardor's other letter keys are taken -- see ArdorKeyCategory usages). */
public final class FreecamKey {

    private static final KeyMapping KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.ardor.freecam", InputConstants.KEY_F6, ArdorKeyCategory.ARDOR));

    private FreecamKey() {}

    public static void register() {
        KeybindTicker.add(KEY, CameraModeController::cycle);
    }
}
