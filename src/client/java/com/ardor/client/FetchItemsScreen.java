package com.ardor.client;

import com.ardor.container.CacheSearch;
import com.ardor.container.CommandCooldowns;
import com.ardor.container.ContainerCache;
import com.ardor.container.FetchQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Plain text-list "Fetch Items" screen -- replaces the earlier vanilla-container-texture item
 * grid (custom pixel hit-testing, an arm-a-slot-then-click-an-inventory-slot flow, a hand-rolled
 * scrollbar) with the same row-list/Button toolkit ItemSourcesScreen already uses: one row per
 * CacheSearch.Group (icon, name, count, source count/cooldown, a Fetch button), rebuilt via
 * clearWidgets()+addRenderableWidget() on every search/scroll change, same convention as that
 * screen's own rebuildAllWidgets(). No custom pixel hit-testing and no player-inventory grid --
 * Fetch drops the item into whatever the first empty inventory slot is (firstEmptyInventorySlot),
 * so there's no "pick a target slot" step at all.
 *
 * Browses the CACHE (last-known contents per source, see container.ContainerCache, now persisted
 * to disk), not a live re-scan on every keystroke. Rescanned on open plus an explicit Refresh
 * button. Search filters live (EditBox.setResponder fires on every edit) via CacheSearch's
 * name-then-component ranking.
 */
public final class FetchItemsScreen extends Screen {

    private static final int ROW_H = 20;
    private static final int FOOTER_H = 40;
    private static final int ICON_SIZE = 16;
    private static final int TEXT_X = 10 + ICON_SIZE + 6; // room for the item icon at x=10

    // Persist across reopen, same reasoning TaskPlannerScreen's own static fields document: a
    // fresh screen instance is constructed every keypress, instance fields would silently reset.
    private static String searchText = "";
    private static boolean allSources = true;
    private static String radiusText = "16";
    private static String statusLine = "";
    private static String resultCountLine = ""; // separate from statusLine so a FetchQueue progress message doesn't get clobbered by the next refresh()'s own count line
    private static int scrollOffsetRows = 0;

    private EditBox searchBox;
    private EditBox radiusBox;

    private List<CacheSearch.Group> results = List.of();
    private int listTop;
    private int rowsVisible;
    private int maxScrollRows = 0;

    public FetchItemsScreen() {
        super(Component.literal("Fetch Items"));
    }

    @Override
    protected void init() {
        FetchQueue.setStatusListener(s -> { statusLine = s; rebuildAllWidgets(); });
        ContainerCache.scanAll();
        rebuildAllWidgets();
    }

    private void rebuildAllWidgets() {
        clearWidgets();

        searchBox = new EditBox(font, 10, 10, 220, 20, Component.literal("Search"));
        searchBox.setHint(Component.literal("Search"));
        searchBox.setMaxLength(200);
        searchBox.setValue(searchText);
        searchBox.setResponder(v -> { searchText = v; scrollOffsetRows = 0; rebuildAllWidgets(); });
        addRenderableWidget(searchBox);
        setInitialFocus(searchBox);

        addRenderableWidget(Checkbox.builder(Component.literal("All"), font)
                .pos(240, 12)
                .selected(allSources)
                .onValueChange((cb, value) -> { allSources = value; radiusBox.active = !value; rebuildAllWidgets(); })
                .build());

        radiusBox = new EditBox(font, 290, 10, 50, 20, Component.literal("Radius"));
        radiusBox.setHint(Component.literal("Radius"));
        radiusBox.setMaxLength(6);
        radiusBox.setValue(radiusText);
        radiusBox.active = !allSources;
        radiusBox.setResponder(v -> { radiusText = v; rebuildAllWidgets(); });
        addRenderableWidget(radiusBox);

        FlowLayout flow = new FlowLayout(350, 10, width - 75, 20, 4, 4);
        int[] pos = flow.next(100);
        addRenderableWidget(Button.builder(Component.literal("Item Sources"), b -> Minecraft.getInstance().setScreen(new ItemSourcesScreen()))
                .bounds(pos[0], pos[1], 100, 20).build());
        pos = flow.next(70);
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> { ContainerCache.scanAll(); rebuildAllWidgets(); })
                .bounds(pos[0], pos[1], 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 65, 10, 55, 20).build());

        listTop = flow.bottom() + 10;
        rowsVisible = Math.max(1, (height - listTop - FOOTER_H) / ROW_H);

        runSearch();

        int totalRows = results.size();
        maxScrollRows = Math.max(0, totalRows - rowsVisible);
        scrollOffsetRows = Math.max(0, Math.min(scrollOffsetRows, maxScrollRows));

        int end = Math.min(results.size(), scrollOffsetRows + rowsVisible);
        for (int i = scrollOffsetRows; i < end; i++) {
            CacheSearch.Group group = results.get(i);
            int y = listTop + (i - scrollOffsetRows) * ROW_H;
            boolean onCooldown = group.fromCommand() && cooldownRemaining(group) > 0;
            Button fetch = addRenderableWidget(Button.builder(
                    Component.literal(onCooldown ? cooldownRemaining(group) + "s" : "Fetch"),
                    b -> onFetchClicked(group)).bounds(width - 75, y, 65, ROW_H - 2).build());
            fetch.active = !onCooldown;
        }
    }

    private void runSearch() {
        int radius = parseRadius();
        LocalPlayer player = Minecraft.getInstance().player;
        var center = player != null ? player.blockPosition() : net.minecraft.core.BlockPos.ZERO;
        results = CacheSearch.groupedSearch(searchText, true, allSources, radius, center);

        // "It still doesn't show the items" -- distinguishing "no sources registered at all" from
        // "sources exist but nothing currently matches" so it's obvious which one this is, instead
        // of both reading as an identical blank "0 item(s)".
        boolean noSourcesAtAll = com.ardor.container.SourceManager.get().currentProfile().sources.isEmpty();
        resultCountLine = results.size() + " item(s)"
                + (results.isEmpty() && noSourcesAtAll ? " (no item sources registered yet -- open a chest, or use Item Sources)" : "");
    }

    private int parseRadius() {
        try {
            return Math.max(0, Integer.parseInt(radiusText.trim()));
        } catch (NumberFormatException e) {
            return 16;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScrollRows > 0) {
            int newOffset = ScrollState.scrolled(scrollOffsetRows, maxScrollRows, scrollY, 1);
            if (newOffset != scrollOffsetRows) {
                scrollOffsetRows = newOffset;
                rebuildAllWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void onFetchClicked(CacheSearch.Group group) {
        if (group.fromCommand()) {
            int remaining = cooldownRemaining(group);
            if (remaining > 0) {
                statusLine = group.displayName() + " is on cooldown for " + remaining + "s.";
                rebuildAllWidgets();
                return;
            }
        }
        LocalPlayer player = Minecraft.getInstance().player;
        int targetSlot = firstEmptyInventorySlot(player);
        if (targetSlot < 0) {
            statusLine = "Inventory is full.";
            rebuildAllWidgets();
            return;
        }
        int amountWanted = Math.min(itemMaxStackSize(group.itemId()), group.totalCount());
        FetchQueue.enqueue(group, targetSlot, amountWanted);
        statusLine = "Queued " + group.displayName() + "...";
        rebuildAllWidgets();
    }

    /** First empty, not-already-queued main-inventory-or-hotbar slot (0-35), or -1 if none. */
    private static int firstEmptyInventorySlot(LocalPlayer player) {
        if (player == null) return -1;
        Inventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).isEmpty() && !FetchQueue.isQueued(i)) return i;
        }
        return -1;
    }

    private int cooldownRemaining(CacheSearch.Group group) {
        if (!group.fromCommand() || group.members().isEmpty()) return 0;
        return CommandCooldowns.remainingSeconds(group.members().get(0).source().id);
    }

    private static int itemMaxStackSize(String itemId) {
        Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(itemId)).orElse(Items.BARRIER);
        return item.getDefaultMaxStackSize();
    }

    private static ItemStack displayStack(String itemId, int count) {
        Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(itemId)).orElse(Items.BARRIER);
        return new ItemStack(item, Math.max(1, count));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xC0101010);
        g.text(font, getTitle().getString(), 10, 1, 0xFFFFFFFF);

        int end = Math.min(results.size(), scrollOffsetRows + rowsVisible);
        for (int i = scrollOffsetRows; i < end; i++) {
            CacheSearch.Group group = results.get(i);
            int y = listTop + (i - scrollOffsetRows) * ROW_H;
            ItemStack icon = displayStack(group.itemId(), group.totalCount());
            g.item(icon, 10, y + 2);
            g.itemDecorations(font, icon, 10, y + 2);
            g.text(font, rowLabel(group), TEXT_X, y + 5, 0xFFFFFFFF);
        }
        if (results.isEmpty()) {
            g.text(font, "No items -- try Refresh, or check Item Sources.", 10, listTop, 0xFF808080);
        }
        if (maxScrollRows > 0) {
            g.text(font, "scroll for more (" + (scrollOffsetRows + 1) + "-" + end + " of " + results.size() + ")",
                    10, listTop + rowsVisible * ROW_H + 4, 0xFF808080);
        }

        g.text(font, resultCountLine, 10, height - FOOTER_H + 8, 0xFFAAAAAA);
        g.text(font, statusLine, 10, height - FOOTER_H + 20, 0xFFAAAAAA);

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private String rowLabel(CacheSearch.Group group) {
        StringBuilder sb = new StringBuilder();
        sb.append(group.displayName()).append(" x").append(group.totalCount());
        if (group.fromCommand()) {
            sb.append("  (command)");
        } else {
            sb.append("  (").append(group.members().size()).append(" source(s))");
        }
        List<String> summary = group.componentSummary();
        if (!summary.isEmpty()) {
            sb.append("  [").append(String.join(", ", summary)).append("]");
        }
        return sb.toString();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
