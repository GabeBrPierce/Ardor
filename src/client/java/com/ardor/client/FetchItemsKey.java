package com.ardor.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

/** Opens FetchItemsScreen. Bound to H by default -- unbound in vanilla, rebindable via Controls if it clashes with another mod. Also reachable via ArdorConfigScreen's "Fetch Items" button (Mod Menu), but kept as its own key too for fast direct access during normal play. */
public final class FetchItemsKey {

    private static final KeyMapping KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.ardor.fetchitems", InputConstants.KEY_H, KeyMapping.Category.MISC));

    private FetchItemsKey() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                while (KEY.consumeClick()) {
                    if (client.screen == null) {
                        client.setScreen(new FetchItemsScreen());
                    }
                }
            } catch (RuntimeException e) {
                System.err.println("[ardor] fetch items key failed: " + e);
            }
        });
    }
}
