package com.ardor.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Registered as the "modmenu" entrypoint in fabric.mod.json -- gives this mod's Mod Menu entry a config screen (ArdorConfigScreen) instead of the greyed-out default. */
public final class ArdorModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ArdorConfigScreen::new;
    }
}
