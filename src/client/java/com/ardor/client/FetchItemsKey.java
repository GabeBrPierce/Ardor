package com.ardor.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/** Opens FetchItemsScreen. Bound to H by default -- unbound in vanilla, rebindable via Controls if it clashes with another mod. Also reachable via ArdorConfigScreen's "Fetch Items" button (Mod Menu), but kept as its own key too for fast direct access during normal play. */
public final class FetchItemsKey {

    private static final KeyMapping KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.ardor.fetchitems", InputConstants.KEY_H, KeyMapping.Category.MISC));

    private FetchItemsKey() {}

    public static void register() {
        KeybindTicker.add(KEY, FetchItemsKey::open);
    }

    private static void open() {
        Minecraft client = Minecraft.getInstance();
        if (client.screen == null) {
            client.setScreen(new FetchItemsScreen());
        }
    }
}
