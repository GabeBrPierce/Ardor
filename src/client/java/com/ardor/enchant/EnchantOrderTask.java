package com.ardor.enchant;

import com.ardor.region.RegionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Drives an ALREADY-OPEN EnchantmentMenu (EnchantRequestScreen IS that menu's screen, so there's
 * nothing to walk to or open) through moving the chosen item in, running EnchantOrderSession, and
 * moving the result back out -- and, separately, the one-time-per-world calibration pass.
 */
public final class EnchantOrderTask {

    private EnchantOrderTask() {}

    public static void run(EnchantmentMenu menu, BlockPos tablePos, int inventorySlot, Map<Holder<Enchantment>, Integer> wanted,
                            Consumer<String> onProgress, Runnable onDone, Consumer<String> onFailed) {
        EnchantSeedTracker tracker = EnchantSeedTracker.load(profileKey()).orElse(null);
        if (tracker == null) {
            onFailed.accept("not calibrated for this world yet");
            return;
        }
        int menuSlot = EnchantContainerOps.menuSlotForInventoryIndex(inventorySlot);
        EnchantContainerOps.moveWholeStack(menu, menuSlot, EnchantOrderSession.ITEM_SLOT, () -> {
            EnchantOrderSession session = new EnchantOrderSession(menu, tablePos, Items.COBBLESTONE);
            session.execute(tracker, wanted, onProgress,
                    () -> returnItemToInventory(menu, onDone, onFailed),
                    failure -> returnItemToInventory(menu, () -> onFailed.accept(failure), onFailed));
        });
    }

    private static void returnItemToInventory(EnchantmentMenu menu, Runnable onDone, Consumer<String> onFailed) {
        if (menu.getSlot(EnchantOrderSession.ITEM_SLOT).getItem().isEmpty()) {
            onDone.run();
            return;
        }
        int keepSlot = EnchantContainerOps.findEmptyInventorySlot(menu);
        if (keepSlot < 0) {
            onFailed.accept("inventory full -- couldn't retrieve the item from the table");
            return;
        }
        EnchantContainerOps.moveWholeStack(menu, EnchantOrderSession.ITEM_SLOT, keepSlot, onDone);
    }

    // ------------------------------------------------------- one-time-per-world calibration, fully automated

    public static void calibrate(EnchantmentMenu menu, BlockPos tablePos, Consumer<String> onProgress, Runnable onDone, Consumer<String> onFailed) {
        String profileKey = profileKey();
        if (EnchantSeedTracker.load(profileKey).isPresent()) {
            onDone.run();
            return;
        }
        calibrationStep(menu, profileKey, 1, -1, -1, onProgress, onDone, onFailed);
    }

    private static void calibrationStep(EnchantmentMenu menu, String profileKey, int step, int obs1, int obs2,
                                         Consumer<String> onProgress, Runnable onDone, Consumer<String> onFailed) {
        onProgress.accept("calibrating: dummy enchant " + Math.min(step, 3) + "/3...");
        swapInFreshBook(menu, () ->
                EnchantContainerOps.clickButton(menu, 0, () -> {
                    int obs = menu.getEnchantmentSeed();
                    if (step == 1) {
                        calibrationStep(menu, profileKey, 2, obs, -1, onProgress, onDone, onFailed);
                    } else if (step == 2) {
                        onProgress.accept("calibrating: rerolling...");
                        ItemDropCycler.dropTimes(Items.COBBLESTONE, EnchantOrderSession.CALIBRATION_THROWS,
                                () -> calibrationStep(menu, profileKey, 3, obs1, obs, onProgress, onDone, onFailed), onFailed);
                    } else {
                        // Not outlier-tolerant like GuidedCalibration (only two observations here) -- but does
                        // still discover the real per-completion cost rather than assuming vanilla's exact 1
                        // (a server-side skill/RPG plugin can draw extra randomness on completion, confirmed
                        // live -- see TODO.md), so this at least works on such a server when nothing else
                        // interferes with these two specific observations.
                        EnchantMath.GapMatch match = EnchantMath.recoverStateAfterGap(obs1, obs2, EnchantOrderSession.MAX_ENCHANT_COMPLETION_GAP);
                        if (match == null) {
                            onFailed.accept("calibration failed: no consistent reading within " + EnchantOrderSession.MAX_ENCHANT_COMPLETION_GAP
                                    + " steps -- something else drew from your character's randomness during calibration");
                        } else {
                            EnchantSeedTracker.recoverAndCalibrate(profileKey, match.state(), match.gap(), EnchantOrderSession.CALIBRATION_THROWS, obs);
                            onDone.run();
                        }
                    }
                }), onFailed);
    }

    private static void swapInFreshBook(EnchantmentMenu menu, Runnable onReady, Consumer<String> onFailed) {
        int bookSlot = EnchantContainerOps.findPlainBookSlot(menu);
        if (bookSlot < 0) {
            onFailed.accept("need at least 3 plain books in inventory to calibrate");
            return;
        }
        if (menu.getSlot(EnchantOrderSession.ITEM_SLOT).getItem().isEmpty()) {
            EnchantContainerOps.moveWholeStack(menu, bookSlot, EnchantOrderSession.ITEM_SLOT, onReady);
            return;
        }
        int parkSlot = EnchantContainerOps.findEmptyInventorySlot(menu);
        if (parkSlot < 0) {
            onFailed.accept("inventory full -- need a free slot during calibration");
            return;
        }
        EnchantContainerOps.moveWholeStack(menu, EnchantOrderSession.ITEM_SLOT, parkSlot, () ->
                EnchantContainerOps.moveWholeStack(menu, bookSlot, EnchantOrderSession.ITEM_SLOT, onReady));
    }

    private static String profileKey() {
        return RegionManager.currentProfileKey();
    }
}
