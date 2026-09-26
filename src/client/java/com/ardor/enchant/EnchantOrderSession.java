package com.ardor.enchant;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Drives one open EnchantmentMenu (with the target item already sitting in ITEM_SLOT) through
 * reroll-by-drop and the dummy/real commit clicks, via the same real handleContainerInput /
 * handleInventoryButtonClick network calls a player's own clicks would send -- see
 * RealCraftingController for the established pattern this follows. Opening the table and moving
 * the item in/out around this is EnchantOrderTask's job, not this class's.
 */
public final class EnchantOrderSession {

    public static final int ITEM_SLOT = 0;
    public static final int CALIBRATION_THROWS = 7;
    /** How many candidate per-completion RNG-step costs to try when recovering a pair of enchant-completion observations -- vanilla is 1, but a server-side skill/RPG plugin can draw extra randomness on completion (confirmed live, see TODO.md), so this isn't assumed. */
    public static final int MAX_ENCHANT_COMPLETION_GAP = 16;

    private final EnchantmentMenu menu;
    private final BlockPos tablePos;
    private final Item junkItem;

    public EnchantOrderSession(EnchantmentMenu menu, BlockPos tablePos, Item junkItem) {
        this.menu = menu;
        this.tablePos = tablePos;
        this.junkItem = junkItem;
    }

    // ------------------------------------------------------- calibration (one-time per world)

    /** Clicks button 0 (cheapest slot) on whatever's currently in the item slot; caller reads menu.getEnchantmentSeed() once onDone fires. */
    public void completeDummyEnchant(Runnable onDone, Consumer<String> onFailed) {
        ItemStack current = menu.getSlot(ITEM_SLOT).getItem();
        if (current.isEmpty()) {
            onFailed.accept("no item in the enchanting slot to complete a dummy enchant on");
            return;
        }
        EnchantContainerOps.clickButton(menu, 0, onDone);
    }

    public void rerollForCalibration(Runnable onDone, Consumer<String> onFailed) {
        ItemDropCycler.dropTimes(junkItem, CALIBRATION_THROWS, onDone, onFailed);
    }

    // ------------------------------------------------------- order execution

    public void execute(EnchantSeedTracker tracker, Map<Holder<Enchantment>, Integer> wanted, Consumer<String> onProgress, Runnable onDone, Consumer<String> onFailed) {
        if (!tracker.matchesLive(menu.getEnchantmentSeed())) {
            tracker.invalidate();
            onFailed.accept("tracked calibration is out of sync with the server (something drew from your character's randomness outside of Ardor since the last calibration or order) -- recalibration needed");
            return;
        }

        ItemStack realItem = menu.getSlot(ITEM_SLOT).getItem();
        if (realItem.isEmpty()) {
            onFailed.accept("no item in the enchanting slot");
            return;
        }
        ItemStack realItemCopy = realItem.copy();

        RegistryAccess registryAccess = Minecraft.getInstance().level.registryAccess();
        int bookshelves = EnchantSimulator.countBookshelves(Minecraft.getInstance().level, tablePos);
        EnchantOrderPlanner.Plan plan = EnchantOrderPlanner.search(registryAccess, tracker, realItemCopy, bookshelves, wanted);
        if (plan == null) {
            onFailed.accept("not reachable at " + bookshelves + " bookshelves within the search bound");
            return;
        }

        if (plan.throwCycles() < 0) {
            onProgress.accept("committing...");
            EnchantContainerOps.clickButton(menu, plan.slot(), () -> {
                tracker.commitDirect();
                onDone.run();
            });
            return;
        }

        onProgress.accept("rerolling (" + plan.throwCycles() + " throws)...");
        ItemDropCycler.dropTimes(junkItem, plan.throwCycles(),
                () -> lockInThenCommit(plan, tracker, onProgress, onDone, onFailed),
                onFailed);
    }

    private void lockInThenCommit(EnchantOrderPlanner.Plan plan, EnchantSeedTracker tracker, Consumer<String> onProgress, Runnable onDone, Consumer<String> onFailed) {
        int parkSlot = EnchantContainerOps.findEmptyInventorySlot(menu);
        if (parkSlot < 0) {
            onFailed.accept("inventory full -- need a free slot to hold your item during the reroll commit");
            return;
        }
        int bookSlot = EnchantContainerOps.findPlainBookSlot(menu);
        if (bookSlot < 0) {
            onFailed.accept("no plain book in inventory for the dummy enchant");
            return;
        }

        onProgress.accept("locking in target seed...");
        EnchantContainerOps.moveWholeStack(menu, ITEM_SLOT, parkSlot, () ->
                EnchantContainerOps.moveWholeStack(menu, bookSlot, ITEM_SLOT, () ->
                        EnchantContainerOps.clickButton(menu, 0, () -> {
                            int keepSlot = EnchantContainerOps.findEmptyInventorySlot(menu);
                            if (keepSlot < 0) {
                                onFailed.accept("inventory full -- need a free slot for the used dummy book");
                                return;
                            }
                            EnchantContainerOps.moveWholeStack(menu, ITEM_SLOT, keepSlot, () ->
                                    EnchantContainerOps.moveWholeStack(menu, parkSlot, ITEM_SLOT, () -> {
                                        onProgress.accept("committing...");
                                        EnchantContainerOps.clickButton(menu, plan.slot(), () -> {
                                            tracker.commitAfterThrows(plan.throwCycles());
                                            onDone.run();
                                        });
                                    })
                            );
                        })
                )
        );
    }
}
