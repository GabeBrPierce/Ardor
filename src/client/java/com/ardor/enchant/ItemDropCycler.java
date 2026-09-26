package com.ardor.enchant;

import com.ardor.game.HotbarUtil;
import com.ardor.game.TickPoll;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.function.Consumer;

/**
 * Burns raw RNG advances on the player's persistent random for free by dropping a cheap junk item
 * repeatedly -- each drop consumes RandomSource calls for toss-velocity variation, which is what
 * lets the enchantment seed be rerolled without spending XP (see EnchantSeedTracker calibration).
 *
 * MUST go through LocalPlayer.drop(boolean) (removes from the SELECTED hotbar slot and sends the
 * real ServerboundPlayerActionPacket(DROP_ITEM)) -- an earlier version of this class called the
 * shared Player.drop(ItemStack, boolean) directly, which on the client only spawns a local,
 * unnetworked ItemEntity (visible to nobody but this client) and never touches the server's
 * inventory or its persistent random at all. Confirmed live: the item appeared to drop but the
 * stack count reverted, and (more importantly, since this whole mechanism depends on it) the
 * server-side random this is meant to advance was never actually touched. Same class of bug
 * HotbarUtil's own doc already warns about (a client-only mutation with no matching packet).
 */
public final class ItemDropCycler {

    private static final int TICKS_BETWEEN_DROPS = 3;

    private ItemDropCycler() {}

    public static void dropTimes(Item junkItem, int times, Runnable onDone, Consumer<String> onFailed) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (!ensureHeld(player, junkItem)) {
            onFailed.accept("no " + junkItem + " in inventory to drop");
            return;
        }
        step(junkItem, times, 0, onDone, onFailed);
    }

    private static void step(Item junkItem, int times, int done, Runnable onDone, Consumer<String> onFailed) {
        if (done >= times) {
            onDone.run();
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (!player.getInventory().getSelectedItem().is(junkItem) && !ensureHeld(player, junkItem)) {
            onFailed.accept("ran out of " + junkItem + " to drop (needed " + (times - done) + " more)");
            return;
        }
        Minecraft.getInstance().gameMode.dropItem(player, false);
        TickPoll.after(TICKS_BETWEEN_DROPS, () -> step(junkItem, times, done + 1, onDone, onFailed));
    }

    /** Puts a stack of `item` into the selected hotbar slot if it isn't already there -- same shape as GameActionController.selectItemInHand, needed here too since dropItem(player, boolean) only ever acts on whatever's currently selected. */
    private static boolean ensureHeld(LocalPlayer player, Item item) {
        Inventory inv = player.getInventory();
        if (inv.getSelectedItem().is(item)) return true;
        int slotIndex = inv.findSlotMatchingItem(new ItemStack(item));
        if (slotIndex < 0) return false;
        if (Inventory.isHotbarSlot(slotIndex)) {
            HotbarUtil.selectSlot(player, slotIndex);
            return true;
        }
        int hotbar = inv.getSelectedSlot();
        Minecraft.getInstance().gameMode.handleContainerInput(player.inventoryMenu.containerId, slotIndex, hotbar, ContainerInput.SWAP, player);
        return true;
    }
}
