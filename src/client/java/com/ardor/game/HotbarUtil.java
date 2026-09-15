package com.ardor.game;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;

/**
 * Every programmatic hotbar switch in this codebase (ToolSelector, GameActionController,
 * AutoEatController, PathfindingController, BridgeActionHandlers) called Inventory.setSelectedSlot
 * directly and nothing else -- that only updates the CLIENT's local selected-slot field. A real
 * player's hotbar key/scroll input goes through Minecraft's own handler, which also sends
 * ServerboundSetCarriedItemPacket so the server knows the selection changed. Without it the server
 * keeps using whatever slot it last heard about, so a mining/attack packet sent right after a
 * programmatic switch is resolved server-side against the OLD held item -- confirmed as the actual
 * cause of "equipped a pickaxe but still broke stone with a sword" (BlockBreaker -> ToolSelector).
 */
public final class HotbarUtil {

    private HotbarUtil() {}

    public static void selectSlot(LocalPlayer player, int slot) {
        Inventory inv = player.getInventory();
        if (!Inventory.isHotbarSlot(slot)) return;
        inv.setSelectedSlot(slot);
        player.connection.send(new ServerboundSetCarriedItemPacket(slot));
    }
}
