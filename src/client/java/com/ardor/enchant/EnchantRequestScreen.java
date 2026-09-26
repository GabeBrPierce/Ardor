package com.ardor.enchant;

import com.ardor.region.RegionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Replaces vanilla's EnchantmentScreen (see EnchantmentScreenOverrideMixin) so this IS what opening
 * a real enchanting table shows: left panel lists your unenchanted equipment, picking one populates
 * a scrollable right panel of every enchantment/level actually reachable at the table's current
 * bookshelf count AND affordable at your current XP. The picker only unlocks once this world's
 * EnchantSeedTracker exists -- until then this runs a fully automated calibration pass (three real
 * dummy enchants on spare books) and shows that progress instead, per explicit instruction: don't
 * enable ordering until an actual enchant has told us the RNG value.
 */
public final class EnchantRequestScreen extends Screen implements MenuAccess<EnchantmentMenu> {

    private static final int LEFT_X = 10;
    private static final int RIGHT_X = 190;
    private static final int PANEL_WIDTH = 170;
    private static final int ROW_H = 18;
    private static final int TOP_Y = 30;

    private final EnchantmentMenu menu;
    private final BlockPos tablePos;
    private final List<Button> leftWidgets = new ArrayList<>();
    private final List<Button> rightWidgets = new ArrayList<>();

    private int leftScroll = 0;
    private int rightScroll = 0;
    private int selectedSlot = -1;
    private ItemStack selectedItem = ItemStack.EMPTY;
    private boolean busy = false;
    private boolean calibrating = false;
    private int lastCandidateCount = -1;
    private Component status = Component.literal("");

    /** Non-null exactly while showing the calibration-failed explanation panel in place of the normal two-panel UI. */
    private String calibrationFailure = null;
    private Button retryButton;
    private Button vanillaScreenButton;

    private boolean showingChoice = false;
    private Button autoCalibrateButton;
    private Button guidedCalibrateButton;

    public EnchantRequestScreen(EnchantmentMenu menu, Inventory inventory, Component title) {
        super(title);
        this.menu = menu;
        this.tablePos = findNearbyTablePos();
    }

    @Override
    public EnchantmentMenu getMenu() {
        return menu;
    }

    private BlockPos findNearbyTablePos() {
        var player = Minecraft.getInstance().player;
        Level level = Minecraft.getInstance().level;
        BlockPos center = player.blockPosition();
        for (BlockPos pos : BlockPos.withinManhattan(center, 6)) {
            if (level.getBlockState(pos).is(Blocks.ENCHANTING_TABLE)) return pos.immutable();
        }
        return center;
    }

    @Override
    protected void init() {
        if (EnchantSeedTracker.load(profileKey()).isEmpty()) {
            showCalibrationChoice();
            return;
        }
        rebuildLeft();
        rebuildRight();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().player.closeContainer();
        super.onClose();
    }

    // ------------------------------------------------------- calibration gate (must happen before ordering is enabled)

    /** First thing shown in an uncalibrated world -- lets the player pick Auto (EnchantOrderTask.calibrate, fully automated, fast, but any stray game event mid-sequence silently breaks it) or Guided (GuidedCalibration, the player clicks everything by hand, slower but nothing but their own actions can interfere). */
    private void showCalibrationChoice() {
        clearAllTransientButtons();
        calibrating = false;
        calibrationFailure = null;
        showingChoice = true;
        status = Component.literal("");

        int buttonY = height / 2;
        autoCalibrateButton = Button.builder(Component.literal("Auto Calibrate"), btn -> startCalibration())
                .bounds(LEFT_X, buttonY, 150, 20)
                .build();
        guidedCalibrateButton = Button.builder(Component.literal("Guided Calibration"), btn -> startGuidedCalibration())
                .bounds(LEFT_X + 160, buttonY, 190, 20)
                .build();
        addRenderableWidget(autoCalibrateButton);
        addRenderableWidget(guidedCalibrateButton);
    }

    private void startCalibration() {
        clearAllTransientButtons();
        showingChoice = false;
        calibrating = true;
        calibrationFailure = null;
        status = Component.literal("Calibrating (one-time per world, needs 3+ plain books)...");
        EnchantOrderTask.calibrate(menu, tablePos,
                progress -> status = Component.literal(progress),
                () -> {
                    calibrating = false;
                    status = Component.literal("");
                    rebuildLeft();
                    rebuildRight();
                },
                failure -> {
                    calibrating = false;
                    calibrationFailure = failure;
                    rebuildFailurePanel();
                });
    }

    // ------------------------------------------------------- guided calibration (player performs every click by hand)

    /**
     * Hands the (still-open) menu to the REAL vanilla EnchantmentScreen -- this screen has no slot
     * widgets of its own (it's a plain Screen, not an AbstractContainerScreen), so there's nothing
     * for the player to click here. GuidedCalibration narrates over chat and watches menu/inventory
     * state independently of whichever screen is showing (registers its own tick listener), so it
     * keeps running underneath vanilla's screen and reopens this one once it finishes either way.
     */
    private void startGuidedCalibration() {
        String problem = GuidedCalibration.checkPrereqs();
        if (problem != null) {
            status = Component.literal(problem);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        GuidedCalibration flow = new GuidedCalibration(menu, profileKey(),
                () -> mc.gui.setScreen(new EnchantRequestScreen(menu, mc.player.getInventory(), getTitle())),
                failure -> mc.gui.setScreen(new EnchantRequestScreen(menu, mc.player.getInventory(), getTitle())));
        flow.start();
        mc.gui.setScreen(new net.minecraft.client.gui.screens.inventory.EnchantmentScreen(menu, mc.player.getInventory(), getTitle()));
    }

    private void clearAllTransientButtons() {
        clearFailurePanelButtons();
        if (autoCalibrateButton != null) removeWidget(autoCalibrateButton);
        if (guidedCalibrateButton != null) removeWidget(guidedCalibrateButton);
        autoCalibrateButton = null;
        guidedCalibrateButton = null;
    }

    // ------------------------------------------------------- calibration-failed explanation panel

    /**
     * Calibration recovers the world's hidden enchantment-RNG state from two back-to-back dummy
     * enchants, then confirms it by predicting a third after a known number of reroll-drops. A
     * mismatch here (see EnchantMath.recoverStateAfter / EnchantSeedTracker.calibrateDropSteps)
     * means something else consumed that same random source between those steps -- another real
     * enchant completed somewhere, another player/mod touched the table, or the calibration sequence
     * got interrupted partway. There's no partial-progress recovery for that: retrying just reruns
     * the three-step sequence from scratch. Previously this just left a one-line status with no
     * explanation and no way forward except closing and reopening the table (which hit the same
     * dead end again, since nothing was ever saved on failure).
     */
    private void rebuildFailurePanel() {
        showingChoice = false;
        clearAllTransientButtons();
        for (Button b : leftWidgets) removeWidget(b);
        leftWidgets.clear();
        for (Button b : rightWidgets) removeWidget(b);
        rightWidgets.clear();

        int buttonY = height - 50;
        retryButton = Button.builder(Component.literal("Back to Calibration Choice"), btn -> showCalibrationChoice())
                .bounds(LEFT_X, buttonY, 190, 20)
                .build();
        vanillaScreenButton = Button.builder(Component.literal("Use Vanilla Enchanting Table"), btn -> openVanillaScreen())
                .bounds(LEFT_X + 200, buttonY, 190, 20)
                .build();
        addRenderableWidget(retryButton);
        addRenderableWidget(vanillaScreenButton);
    }

    private void clearFailurePanelButtons() {
        if (retryButton != null) removeWidget(retryButton);
        if (vanillaScreenButton != null) removeWidget(vanillaScreenButton);
        retryButton = null;
        vanillaScreenButton = null;
    }

    /** Drops straight to real vanilla EnchantmentScreen for this same still-open menu -- bypasses EnchantmentScreenOverrideMixin entirely since that only intercepts MenuScreens' lookup for opening a NEW menu, not a direct setScreen call. Lets the player enchant normally without Ardor's automation while calibration stays broken. */
    private void openVanillaScreen() {
        Minecraft mc = Minecraft.getInstance();
        mc.gui.setScreen(new net.minecraft.client.gui.screens.inventory.EnchantmentScreen(menu, mc.player.getInventory(), getTitle()));
    }

    // ------------------------------------------------------- left panel

    void refreshIfStale() {
        if (busy || calibrating) return;
        int count = countCandidates();
        if (count != lastCandidateCount) rebuildLeft();
    }

    private int countCandidates() {
        Inventory inv = Minecraft.getInstance().player.getInventory();
        int c = 0;
        for (int i = 0; i < 36; i++) if (isCandidate(inv.getItem(i))) c++;
        return c;
    }

    private static boolean isCandidate(ItemStack stack) {
        return !stack.isEmpty() && EnchantmentHelper.canStoreEnchantments(stack) && !EnchantmentHelper.hasAnyEnchantments(stack);
    }

    private void rebuildLeft() {
        for (Button b : leftWidgets) removeWidget(b);
        leftWidgets.clear();
        if (calibrating) return;

        Inventory inv = Minecraft.getInstance().player.getInventory();
        int y = TOP_Y - leftScroll;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (!isCandidate(stack)) continue;
            count++;
            if (y >= TOP_Y - ROW_H && y + ROW_H <= height - 30) {
                int slot = i;
                ItemStack copy = stack.copy();
                Button b = Button.builder(stack.getHoverName(), btn -> selectItem(slot, copy))
                        .bounds(LEFT_X, y, PANEL_WIDTH, 16)
                        .build();
                b.active = !busy;
                leftWidgets.add(b);
                addRenderableWidget(b);
            }
            y += ROW_H;
        }
        lastCandidateCount = count;

        if (selectedSlot >= 0 && !isCandidate(inv.getItem(selectedSlot))) {
            selectedSlot = -1;
            selectedItem = ItemStack.EMPTY;
            rebuildRight();
        }
    }

    private void selectItem(int slot, ItemStack stack) {
        selectedSlot = slot;
        selectedItem = stack;
        rightScroll = 0;
        rebuildRight();
    }

    // ------------------------------------------------------- right panel

    private void rebuildRight() {
        for (Button b : rightWidgets) removeWidget(b);
        rightWidgets.clear();
        if (calibrating || selectedItem.isEmpty()) return;

        Level level = Minecraft.getInstance().level;
        var tableEnchants = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(EnchantmentTags.IN_ENCHANTING_TABLE);
        if (tableEnchants.isEmpty()) return;

        EnchantSeedTracker tracker = EnchantSeedTracker.load(profileKey()).orElse(null);
        if (tracker == null) return; // shouldn't happen once past calibration, but stay defensive
        if (!tracker.matchesLive(menu.getEnchantmentSeed())) {
            // Something drew from the player's own randomness outside of Ardor since the last calibration/order
            // (a manual enchant/drop, damage, a server-side skill plugin, etc.) -- catch it here, before the
            // picker shows predictions computed from a now-wrong baseline, rather than only at commit time.
            tracker.invalidate();
            showCalibrationChoice();
            return;
        }

        RegistryAccess registryAccess = level.registryAccess();
        int bookshelves = EnchantSimulator.countBookshelves(level, tablePos);
        int xp = Minecraft.getInstance().player.experienceLevel;

        int y = TOP_Y - rightScroll;
        for (Holder<Enchantment> holder : tableEnchants.get()) {
            Enchantment ench = holder.value();
            if (!ench.canEnchant(selectedItem)) continue;

            for (int lvl = ench.getMaxLevel(); lvl >= 1; lvl--) {
                EnchantOrderPlanner.Plan plan = EnchantOrderPlanner.search(registryAccess, tracker, selectedItem, bookshelves,
                        Map.of(holder, lvl), EnchantOrderPlanner.QUICK_CHECK_THROW_CYCLES);
                if (plan == null || plan.cost() > xp) continue;

                if (y >= TOP_Y - ROW_H && y + ROW_H <= height - 30) {
                    int chosenLevel = lvl;
                    String label = ench.description().getString() + " " + chosenLevel + " (cost " + plan.cost() + ")";
                    Button b = Button.builder(Component.literal(label), btn -> onLevelChosen(holder, chosenLevel))
                            .bounds(RIGHT_X, y, PANEL_WIDTH, 16)
                            .build();
                    b.active = !busy;
                    rightWidgets.add(b);
                    addRenderableWidget(b);
                }
                y += ROW_H;
                break; // one row per enchantment: the best level currently reachable AND affordable
            }
        }
        status = Component.literal("Pick an enchantment for " + selectedItem.getHoverName().getString() + ".");
    }

    private void onLevelChosen(Holder<Enchantment> holder, int level) {
        if (busy || calibrating || selectedSlot < 0) return;
        busy = true;
        setAllActive(false);
        status = Component.literal("Working...");
        EnchantOrderTask.run(menu, tablePos, selectedSlot, Map.of(holder, level),
                progress -> status = Component.literal(progress),
                () -> {
                    status = Component.literal("Done.");
                    busy = false;
                    selectedSlot = -1;
                    selectedItem = ItemStack.EMPTY;
                    rebuildLeft();
                    rebuildRight();
                },
                failure -> {
                    status = Component.literal("Failed: " + failure);
                    busy = false;
                    setAllActive(true);
                });
    }

    private void setAllActive(boolean active) {
        for (Button b : leftWidgets) b.active = active;
        for (Button b : rightWidgets) b.active = active;
    }

    private static String profileKey() {
        return RegionManager.currentProfileKey();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX < RIGHT_X) {
            leftScroll = Math.max(0, leftScroll - (int) (scrollY * ROW_H));
            rebuildLeft();
        } else {
            rightScroll = Math.max(0, rightScroll - (int) (scrollY * ROW_H));
            rebuildRight();
        }
        return true;
    }

    private static final Component CALIBRATION_EXPLANATION = Component.literal(
            "Ardor calibrates once per world by reading the hidden enchantment randomness from two "
            + "real enchants, then confirms it by dropping junk items a set number of times and "
            + "checking that a third enchant matches the predicted result. That confirmation didn't "
            + "match, which means something else used the same random source in between -- another "
            + "enchant completing somewhere, another player or mod acting on the table, or the "
            + "sequence getting interrupted partway through. Retrying reruns all three steps from "
            + "scratch; nothing from a failed attempt is kept.");

    private static final Component CHOICE_EXPLANATION = Component.literal(
            "The Enchantment Calculator needs to be calibrated before it can predict enchantments -- "
            + "a one-time step per world that reads your character's hidden enchantment randomness. "
            + "Auto Calibrate does it for you automatically (fast, but any other game event -- damage, "
            + "another enchant, equipment breaking -- happening mid-sequence silently breaks it). "
            + "Guided Calibration hands you the real enchanting table and walks you through it by "
            + "hand over chat, one step at a time, so nothing but your own actions can interfere.");

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0x80101010);
        if (calibrationFailure != null) {
            g.text(font, "Calibration Failed", LEFT_X, 10, 0xFFFF5555);
            g.textWithWordWrap(font, CALIBRATION_EXPLANATION, LEFT_X, 30, width - LEFT_X * 2, 0xFFFFFFFF);
            g.text(font, "Details: " + calibrationFailure, LEFT_X, height - 70, 0xFFAAAAAA);
        } else if (showingChoice) {
            g.text(font, "Enchantment Calculator", LEFT_X, 10, 0xFFFFFFFF);
            g.textWithWordWrap(font, CHOICE_EXPLANATION, LEFT_X, 30, width - LEFT_X * 2, 0xFFFFFFFF);
            g.text(font, status.getString(), LEFT_X, height - 20, 0xFFFF5555);
        } else {
            g.text(font, "Unenchanted items", LEFT_X, 10, 0xFFFFFFFF);
            g.text(font, "Enchantments", RIGHT_X, 10, 0xFFFFFFFF);
            g.text(font, status.getString(), LEFT_X, height - 20, 0xFFFFFF55);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
