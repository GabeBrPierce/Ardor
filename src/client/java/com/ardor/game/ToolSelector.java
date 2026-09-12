package com.ardor.game;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Finds and equips the best available tool in the player's inventory for a
 * target block, using the modern data-component Tool system directly
 * (Tool.getMiningSpeed(BlockState)/isCorrectForDrops(BlockState), confirmed
 * via jar inspection) instead of reimplementing Minecraft's own tool-tag
 * matching by hand.
 *
 * CORRECTION (2026-09-10, see TODO.md): the claim below that no
 * BlockState.requiresCorrectToolForDrops()-equivalent exists was wrong --
 * it does exist, as BlockBehaviour.BlockStateBase.requiresCorrectToolForDrops(),
 * which BlockState extends; a `javap` limited to BlockState's own declared
 * members (what the original check here was based on) doesn't show
 * inherited members, which is how it was missed. Confirmed directly against
 * BlockBehaviour$BlockStateBase this pass. This method still doesn't hard-
 * require correctness (see ToolTierRequirements/PathfindingController for
 * that -- a pre-flight check before mining even starts, not a change to the
 * tool-selection heuristic here) -- it just prefers isCorrectForDrops()=true
 * tools first, then highest getMiningSpeed() within whichever group has
 * candidates. Leaves the currently-held item alone if nothing in inventory
 * beats it (including bare hands, which this never explicitly considers --
 * there's nothing to "equip" for that case).
 */
public final class ToolSelector {

    private static final int INVENTORY_SLOTS = 36; // 9 hotbar + 27 storage -- stable vanilla constant, not slot-count-queried

    private ToolSelector() {}

    public static void equipBestTool(Inventory inv, BlockState blockState) {
        int bestSlot = -1;
        boolean bestCorrect = false;
        float bestSpeed = -1;

        for (int slot = 0; slot < INVENTORY_SLOTS; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.isEmpty()) continue;
            Tool tool = stack.get(DataComponents.TOOL);
            if (tool == null) continue;

            boolean correct = tool.isCorrectForDrops(blockState);
            float speed = tool.getMiningSpeed(blockState);
            boolean better = bestSlot < 0 || (correct && !bestCorrect) || (correct == bestCorrect && speed > bestSpeed);
            if (better) {
                bestSlot = slot;
                bestCorrect = correct;
                bestSpeed = speed;
            }
        }

        if (bestSlot < 0) return; // nothing in inventory has a Tool component better than what's already held

        if (Inventory.isHotbarSlot(bestSlot)) {
            inv.setSelectedSlot(bestSlot);
            return;
        }
        int hotbar = inv.getSelectedSlot();
        ItemStack held = inv.getItem(hotbar);
        ItemStack toEquip = inv.getItem(bestSlot);
        inv.setItem(hotbar, toEquip);
        inv.setItem(bestSlot, held);
    }
}
