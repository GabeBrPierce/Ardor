package com.ardor.enchant;

import com.ardor.game.TickPoll;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/** Shared real-network container-click primitives used by both EnchantOrderSession (per-order reroll/commit) and EnchantOrderTask (opening the table, moving the target item in/out). */
final class EnchantContainerOps {

    static final int STEP_PACE_TICKS = 4;

    private EnchantContainerOps() {}

    static void clickButton(AbstractContainerMenu menu, int slot, Runnable onDone) {
        Minecraft.getInstance().gameMode.handleInventoryButtonClick(menu.containerId, slot);
        TickPoll.after(STEP_PACE_TICKS, onDone);
    }

    /** Moves the whole stack from `from` to `to`; `to` must start empty. */
    static void moveWholeStack(AbstractContainerMenu menu, int from, int to, Runnable onDone) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        mc.gameMode.handleContainerInput(menu.containerId, from, 0, ContainerInput.PICKUP, player);
        TickPoll.after(STEP_PACE_TICKS, () -> {
            mc.gameMode.handleContainerInput(menu.containerId, to, 0, ContainerInput.PICKUP, player);
            TickPoll.after(STEP_PACE_TICKS, onDone);
        });
    }

    /**
     * EnchantmentMenu's own 2 slots come first (menu index 0,1), then addStandardInventorySlots adds
     * main inventory (raw index 9-35) BEFORE the hotbar (raw index 0-8) -- confirmed via javap on
     * AbstractContainerMenu.addInventoryExtendedSlots/addInventoryHotbarSlots -- so the mapping is
     * NOT a flat +2 offset across the whole range.
     */
    static int menuSlotForInventoryIndex(int invIndex) {
        if (invIndex >= 9 && invIndex <= 35) return invIndex - 7;
        if (invIndex >= 0 && invIndex <= 8) return invIndex + 29;
        throw new IllegalArgumentException("inventory index out of range: " + invIndex);
    }

    static int findEmptyInventorySlot(AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) {
            if (slot.index >= 2 && slot.getItem().isEmpty()) return slot.index;
        }
        return -1;
    }

    static int findPlainBookSlot(AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) {
            ItemStack stack = slot.getItem();
            if (slot.index >= 2 && stack.is(Items.BOOK) && !EnchantmentHelper.hasAnyEnchantments(stack)) return slot.index;
        }
        return -1;
    }
}
