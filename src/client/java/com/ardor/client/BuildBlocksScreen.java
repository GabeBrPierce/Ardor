package com.ardor.client;

import com.ardor.container.CacheSearch;
import com.ardor.game.BuildAreaController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Build Blocks Within" from the Area Selection follow-up wheel -- the setup screen for
 * BuildAreaController, one row per candidate block type found across three sources: the player's
 * own inventory, nearby registered containers (CacheSearch, same index Fetch Items/Item Sources
 * use), and blocks of that same type simply standing nearby in the world (a bounded cube scan --
 * "available to go mine more of," not something already owned). Rows are scanned ONCE in init()
 * and held in memory (candidates), mutated in place by every checkbox/text edit and re-read by
 * onStart() -- same "screen owns the real state, rebuild the view from it" convention every other
 * list screen here uses (WheelEditScreen, KeybindsScreen), forced here too since Checkbox has no
 * public setter (confirmed via javap) -- the only way to reflect a computed selected/useX value in
 * a checkbox's own display is to rebuild it fresh from source-of-truth.
 *
 * Checkbox linking, per the request verbatim: toggling any of Inv/Cont/Mine recomputes Use
 * (selected = useInventory || useContainers || useBreakBlocks); toggling Use ON turns on every
 * sub-checkbox that has something to offer (inventoryCount>0 / containerCount>0 /
 * environmentAvailable), toggling it OFF clears all three.
 *
 * requestedCount defaults to the full combined inventory+container total and is user-editable,
 * clamped to [0, that total] -- "break blocks"/environment sourcing is NOT folded into that total
 * (mining is a renewable source, not a fixed count); BuildAreaController.hasUnlimitedSource is what
 * lets the top summary/Start button treat a Mine-enabled row as covering whatever's left.
 */
public final class BuildBlocksScreen extends Screen {

    private static final int ROW_TOP = 70;
    private static final int ROW_H = 24;
    private static final int FOOTER_H = 20;
    private static final int SCROLLBAR_X_MARGIN = 8;
    private static final int CONTAINER_SEARCH_RADIUS = 32;
    private static final int ENV_SCAN_RADIUS = 16;

    private static final class Candidate {
        final Block block;
        final Item item;
        final String displayName;
        int inventoryCount;
        int containerCount;
        boolean environmentAvailable;
        int requestedCount;
        boolean useInventory, useContainers, useBreakBlocks, selected;

        Candidate(Block block, Item item) {
            this.block = block;
            this.item = item;
            this.displayName = block.getName().getString();
        }

        int totalOwned() {
            return inventoryCount + containerCount;
        }
    }

    private final AABB box;
    private List<BlockPos> targetPositions;
    private int requiredCount;
    private List<Candidate> candidates;
    private int scrollOffset;
    private int rowsVisible;
    private Button startButton;

    public BuildBlocksScreen(AABB box) {
        super(Component.literal("Construction Source Menu"));
        this.box = box;
    }

    @Override
    protected void init() {
        if (candidates == null) {
            Level level = Minecraft.getInstance().level;
            targetPositions = level != null ? BuildAreaController.enumerate(box, level) : List.of();
            requiredCount = targetPositions.size();
            candidates = scanCandidates();
        }
        rebuildAllWidgets();
    }

    private List<Candidate> scanCandidates() {
        LocalPlayer player = Minecraft.getInstance().player;
        Level level = player != null ? player.level() : null;
        if (player == null || level == null) return List.of();

        Map<Block, Candidate> byBlock = new LinkedHashMap<>();

        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi)) continue;
            byBlock.computeIfAbsent(bi.getBlock(), b -> new Candidate(b, stack.getItem())).inventoryCount += stack.getCount();
        }

        for (CacheSearch.Group group : CacheSearch.groupedSearch("", true, false, CONTAINER_SEARCH_RADIUS, player.blockPosition())) {
            if (group.fromCommand()) continue;
            Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(group.itemId())).orElse(null);
            if (!(item instanceof BlockItem bi)) continue;
            byBlock.computeIfAbsent(bi.getBlock(), b -> new Candidate(b, item)).containerCount += group.totalCount();
        }

        BlockPos center = player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-ENV_SCAN_RADIUS, -ENV_SCAN_RADIUS, -ENV_SCAN_RADIUS),
                center.offset(ENV_SCAN_RADIUS, ENV_SCAN_RADIUS, ENV_SCAN_RADIUS))) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.getDestroySpeed(level, pos) < 0) continue;
            Block block = state.getBlock();
            Item item = block.asItem();
            if (!(item instanceof BlockItem)) continue; // no placeable item form (e.g. fire, most fluids already excluded by destroySpeed)
            byBlock.computeIfAbsent(block, b -> new Candidate(b, item)).environmentAvailable = true;
        }

        List<Candidate> list = new ArrayList<>(byBlock.values());
        for (Candidate c : list) {
            c.requestedCount = c.totalOwned();
            c.useInventory = c.inventoryCount > 0;
            c.useContainers = c.containerCount > 0;
            c.selected = c.useInventory || c.useContainers;
        }
        list.sort(Comparator.comparing(c -> c.displayName));
        return list;
    }

    private void rebuildAllWidgets() {
        clearWidgets();

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(10, 10, 70, 20).build());
        startButton = addRenderableWidget(Button.builder(Component.literal("Start"), b -> onStart())
                .bounds(width - 80, 10, 70, 20).build());
        startButton.active = isSatisfied();

        rowsVisible = Math.max(1, (height - ROW_TOP - FOOTER_H) / ROW_H);
        int maxOffset = Math.max(0, candidates.size() - rowsVisible);
        scrollOffset = Math.min(scrollOffset, maxOffset);

        int y = ROW_TOP;
        int end = Math.min(candidates.size(), scrollOffset + rowsVisible);
        for (int i = scrollOffset; i < end; i++) {
            addCandidateRow(candidates.get(i), y);
            y += ROW_H;
        }
    }

    private void addCandidateRow(Candidate c, int y) {
        int total = c.totalOwned();

        EditBox countBox = new EditBox(font, 150, y + 2, 45, ROW_H - 6, Component.literal("Count"));
        countBox.setValue(Integer.toString(c.requestedCount));
        countBox.setMaxLength(6);
        countBox.setResponder(v -> {
            try {
                c.requestedCount = Math.max(0, Math.min(total, Integer.parseInt(v.trim())));
            } catch (NumberFormatException ignored) { /* mid-edit (empty, partial "-") -- leave the value alone until it parses */ }
            updateStartButton();
        });
        addRenderableWidget(countBox);

        Checkbox invBox = Checkbox.builder(Component.literal("Inv"), font)
                .pos(320, y + 4).selected(c.useInventory)
                .onValueChange((cb, v) -> { c.useInventory = v; onSubToggle(c); })
                .build();
        invBox.active = c.inventoryCount > 0;
        addRenderableWidget(invBox);

        Checkbox contBox = Checkbox.builder(Component.literal("Cont"), font)
                .pos(380, y + 4).selected(c.useContainers)
                .onValueChange((cb, v) -> { c.useContainers = v; onSubToggle(c); })
                .build();
        contBox.active = c.containerCount > 0;
        addRenderableWidget(contBox);

        Checkbox mineBox = Checkbox.builder(Component.literal("Mine"), font)
                .pos(445, y + 4).selected(c.useBreakBlocks)
                .onValueChange((cb, v) -> { c.useBreakBlocks = v; onSubToggle(c); })
                .build();
        mineBox.active = c.environmentAvailable;
        addRenderableWidget(mineBox);

        addRenderableWidget(Checkbox.builder(Component.literal("Use"), font)
                .pos(510, y + 4).selected(c.selected)
                .onValueChange((cb, v) -> onSelectToggle(c, v))
                .build());
    }

    /** Any sub-checkbox changing recomputes Use -- checked the instant at least one source is on, unchecked the instant none are. */
    private void onSubToggle(Candidate c) {
        c.selected = c.useInventory || c.useContainers || c.useBreakBlocks;
        rebuildAllWidgets();
    }

    /** Use itself: ON turns on every source this row actually has something in, OFF clears all three. */
    private void onSelectToggle(Candidate c, boolean value) {
        c.selected = value;
        if (value) {
            c.useInventory = c.inventoryCount > 0;
            c.useContainers = c.containerCount > 0;
            c.useBreakBlocks = c.environmentAvailable;
        } else {
            c.useInventory = false;
            c.useContainers = false;
            c.useBreakBlocks = false;
        }
        rebuildAllWidgets();
    }

    private boolean isSatisfied() {
        if (requiredCount == 0) return false;
        int committed = 0;
        boolean unlimited = false;
        for (Candidate c : candidates) {
            if (!c.selected) continue;
            committed += c.requestedCount;
            if (c.useBreakBlocks) unlimited = true;
        }
        return unlimited || committed >= requiredCount;
    }

    private void updateStartButton() {
        if (startButton != null) startButton.active = isSatisfied();
    }

    private void onStart() {
        List<BuildAreaController.PlanEntry> plan = new ArrayList<>();
        for (Candidate c : candidates) {
            if (!c.selected) continue;
            if (c.requestedCount <= 0 && !c.useBreakBlocks) continue;
            plan.add(new BuildAreaController.PlanEntry(c.item, c.requestedCount, c.useInventory, c.useContainers, c.useBreakBlocks));
        }
        Minecraft.getInstance().gui.setScreen(null);
        BuildAreaController.start(targetPositions, plan);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxOffset = Math.max(0, candidates.size() - rowsVisible);
        if (maxOffset > 0) {
            int newOffset = ScrollState.scrolled(scrollOffset, maxOffset, scrollY, 1);
            if (newOffset != scrollOffset) {
                scrollOffset = newOffset;
                rebuildAllWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int maxOffset = Math.max(0, candidates.size() - rowsVisible);
        if (maxOffset > 0) {
            int newOffset = Scrollbar.clickedOffset(event.x(), event.y(), width - SCROLLBAR_X_MARGIN, ROW_TOP, rowsVisible * ROW_H, candidates.size(), rowsVisible);
            if (newOffset >= 0) {
                scrollOffset = newOffset;
                rebuildAllWidgets();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xC0101010);
        g.text(font, "Construction Source Menu", 10, 1, 0xFFFFFFFF);
        renderSummary(g);

        int end = Math.min(candidates.size(), scrollOffset + rowsVisible);
        for (int i = scrollOffset; i < end; i++) {
            Candidate c = candidates.get(i);
            int y = ROW_TOP + (i - scrollOffset) * ROW_H;
            g.item(new ItemStack(c.item), 10, y + 4);
            g.text(font, c.displayName, 30, y + 8, 0xFFFFFFFF);
            g.text(font, "/ " + c.totalOwned(), 198, y + 8, 0xFFAAAAAA);
            g.text(font, sourcesText(c), 235, y + 8, 0xFF808080);
        }
        if (candidates.isEmpty()) {
            g.text(font, "No available blocks found -- check inventory, nearby containers, or nearby terrain.", 10, ROW_TOP, 0xFF808080);
        } else if (candidates.size() > rowsVisible) {
            Scrollbar.render(g, width - SCROLLBAR_X_MARGIN, ROW_TOP, rowsVisible * ROW_H, candidates.size(), rowsVisible, scrollOffset);
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private static String sourcesText(Candidate c) {
        List<String> parts = new ArrayList<>();
        if (c.inventoryCount > 0) parts.add("Inv");
        if (c.containerCount > 0) parts.add("Cont");
        if (c.environmentAvailable) parts.add("Nearby");
        return String.join(", ", parts);
    }

    /** Top-of-screen summary: up to 3 icons of the highest-committed selected block types, then "committed / required" -- red until a Mine-enabled row is selected or the committed total covers the whole area, matching Start's own enablement rule exactly. */
    private void renderSummary(GuiGraphicsExtractor g) {
        List<Candidate> topSelected = candidates.stream()
                .filter(c -> c.selected && c.requestedCount > 0)
                .sorted(Comparator.comparingInt((Candidate c) -> c.requestedCount).reversed())
                .toList();

        int committed = 0;
        boolean unlimited = false;
        for (Candidate c : candidates) {
            if (!c.selected) continue;
            committed += c.requestedCount;
            if (c.useBreakBlocks) unlimited = true;
        }
        boolean satisfied = unlimited || committed >= requiredCount;

        int x = 10;
        int y = 20;
        for (int i = 0; i < Math.min(3, topSelected.size()); i++) {
            g.item(new ItemStack(topSelected.get(i).item), x, y);
            x += 18;
        }
        String suffix = unlimited ? "+ (mining as needed)" : "";
        g.text(font, committed + " / " + requiredCount + " blocks" + suffix, x + 4, y + 4, satisfied ? 0xFF55CC55 : 0xFFFF5555);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
