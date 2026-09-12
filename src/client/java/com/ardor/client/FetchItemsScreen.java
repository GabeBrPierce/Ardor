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
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * The "big chest" UI -- browses the CACHE (last-known contents per source, see
 * container.ContainerCache), not a live re-scan on every keystroke. Rescans every enabled source
 * once on open (cheap: each PHYSICAL/SUBCONTAINER source scan is one known-position lookup, see
 * ContainerCache's own class doc) plus an explicit Refresh button.
 *
 * Background is the real vanilla generic container slot texture (textures/gui/container/
 * generic_54.png via RenderPipelines.GUI_TEXTURED), confirmed via javap disassembly of
 * ContainerScreen.extractBackground against the real 26.1.2 client jar -- see TODO.md. IMAGE_HEIGHT
 * (114 + rows*18) is ContainerScreen's own real constructor formula, same javap pass -- the extra
 * height below the container grid is exactly where vanilla's own player-inventory slot art lives
 * in this texture, which the player-inventory block below reuses instead of cropping it away.
 *
 * The grid shows one aggregated CacheSearch.Group per slot (same item + same components across
 * however many sources hold it, see that class's own doc -- durability included, via
 * game.ComponentSummarizer), not one slot per physical chest slot. Clicking a grid slot "arms" it
 * (a click-to-target-a-slot flow, not an immediate fetch): a highlighted border shows which group
 * is armed, and clicking an empty player-inventory slot commits the fetch to land there,
 * pulling up to that item's real max stack size from however many sources it takes
 * (container.FetchQueue's multi-source fulfillment loop) -- right-click, or clicking the same
 * armed group again, cancels. Queued/in-flight fetches show a washed-out "ghost" icon over their
 * target slot until the real item lands (FetchQueue.ghostFor); everything is non-blocking, so more
 * items can be armed and queued while earlier ones are still being walked to.
 *
 * Search filters live (EditBox.setResponder fires on every edit -- functionally the same "filter
 * as you type" the spec's "on key release" asked for) via CacheSearch's name-then-component
 * ranking. A search producing more than GRID_COLS*GRID_ROWS matches is no longer truncated --
 * scrollOffsetRows pages through the full result list via the custom scrollbar to the grid's right
 * (drag the thumb, or scroll wheel anywhere over the screen), since there's no scrollbar widget
 * anywhere else in this codebase's screen toolkit to reuse.
 */
public final class FetchItemsScreen extends Screen {

    private static final Identifier CONTAINER_BACKGROUND = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
    private static final int GRID_COLS = 9;
    private static final int GRID_ROWS = 6;
    private static final int SLOT_SIZE = 18;
    private static final int IMAGE_WIDTH = 176;
    private static final int IMAGE_HEIGHT = 114 + GRID_ROWS * SLOT_SIZE;
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int SCROLLBAR_TRACK_HEIGHT = GRID_ROWS * SLOT_SIZE;

    // Persist across reopen, same reasoning TaskPlannerScreen's own static fields document: a
    // fresh screen instance is constructed every keypress, instance fields would silently reset.
    private static String searchText = "";
    private static boolean allSources = true;
    private static String radiusText = "16";
    private static String statusLine = "";
    private static String resultCountLine = ""; // separate from statusLine so a FetchQueue progress message doesn't get clobbered by the next refresh()'s own count line
    private static CacheSearch.Group armedGroup;

    private EditBox searchBox;
    private EditBox radiusBox;
    private Checkbox allCheckbox;

    private List<CacheSearch.Group> results = List.of();
    private int gridX, gridY;
    private int scrollOffsetRows = 0;
    private int maxScrollRows = 0;
    private boolean draggingScrollbar = false;

    public FetchItemsScreen() {
        super(Component.literal("Fetch Items"));
    }

    @Override
    protected void init() {
        clearWidgets();

        searchBox = new EditBox(font, 10, 10, 220, 20, Component.literal("Search"));
        searchBox.setMaxLength(200);
        searchBox.setValue(searchText);
        searchBox.setResponder(v -> { searchText = v; refresh(); });
        addRenderableWidget(searchBox);
        setInitialFocus(searchBox);

        allCheckbox = addRenderableWidget(Checkbox.builder(Component.literal("All"), font)
                .pos(240, 12)
                .selected(allSources)
                .onValueChange((cb, value) -> { allSources = value; radiusBox.active = !value; refresh(); })
                .build());

        radiusBox = new EditBox(font, 290, 10, 50, 20, Component.literal("Radius"));
        radiusBox.setMaxLength(6);
        radiusBox.setValue(radiusText);
        radiusBox.active = !allSources;
        radiusBox.setResponder(v -> { radiusText = v; refresh(); });
        addRenderableWidget(radiusBox);

        addRenderableWidget(Button.builder(Component.literal("Item Sources"), b -> Minecraft.getInstance().setScreen(new ItemSourcesScreen()))
                .bounds(350, 10, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> { ContainerCache.scanAll(); refresh(); })
                .bounds(456, 10, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 65, 10, 55, 20).build());

        gridX = (width - IMAGE_WIDTH) / 2;
        gridY = 44;

        FetchQueue.setStatusListener(s -> { statusLine = s; refresh(); });

        ContainerCache.scanAll();
        refresh();
    }

    private void refresh() {
        int radius = parseRadius();
        LocalPlayer player = Minecraft.getInstance().player;
        var center = player != null ? player.blockPosition() : net.minecraft.core.BlockPos.ZERO;
        results = CacheSearch.groupedSearch(searchText, true, allSources, radius, center);

        int totalRows = (results.size() + GRID_COLS - 1) / GRID_COLS;
        maxScrollRows = Math.max(0, totalRows - GRID_ROWS);
        scrollOffsetRows = Math.min(scrollOffsetRows, maxScrollRows);

        resultCountLine = results.size() + " item(s)";
    }

    private int parseRadius() {
        try {
            return Math.max(0, Integer.parseInt(radiusText.trim()));
        } catch (NumberFormatException e) {
            return 16;
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 1 && armedGroup != null) { // right-click cancels an armed selection
            armedGroup = null;
            statusLine = "Cancelled.";
            return true;
        }
        if (event.button() == 0) {
            if (maxScrollRows > 0 && withinScrollbarThumb(event.x(), event.y())) {
                draggingScrollbar = true;
                return true;
            }
            int invSlot = inventorySlotAt(event.x(), event.y());
            if (invSlot >= 0 && armedGroup != null) {
                commitArmedFetch(invSlot);
                return true;
            }
            int slot = slotAt(event.x(), event.y());
            if (slot >= 0) {
                int index = scrollOffsetRows * GRID_COLS + slot;
                if (index < results.size()) {
                    onGroupClicked(results.get(index));
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingScrollbar) {
            updateScrollFromMouse(event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingScrollbar) {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScrollRows > 0) {
            scrollOffsetRows = Math.max(0, Math.min(maxScrollRows, scrollOffsetRows - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private int slotAt(double mouseX, double mouseY) {
        int localX = (int) mouseX - gridX - 8;
        int localY = (int) mouseY - gridY - 18;
        if (localX < 0 || localY < 0) return -1;
        int col = localX / SLOT_SIZE;
        int row = localY / SLOT_SIZE;
        if (col >= GRID_COLS || row >= GRID_ROWS || localX % SLOT_SIZE >= 16 || localY % SLOT_SIZE >= 16) return -1;
        return row * GRID_COLS + col;
    }

    /** Player-inventory hit test (main 3 rows -> Inventory slots 9-35, hotbar -> 0-8) -- same coordinate math the render pass below uses. */
    private int inventorySlotAt(double mouseX, double mouseY) {
        int invY = gridY + GRID_ROWS * SLOT_SIZE + 13;
        int localX = (int) mouseX - gridX - 8;
        int localY = (int) mouseY - invY;
        if (localX < 0 || localY < 0) return -1;
        int col = localX / SLOT_SIZE;
        if (col >= 9 || localX % SLOT_SIZE >= 16) return -1;
        if (localY < 3 * SLOT_SIZE) {
            int row = localY / SLOT_SIZE;
            if (localY % SLOT_SIZE >= 16) return -1;
            return row * 9 + col + 9;
        }
        int hotbarY = localY - 58;
        if (hotbarY >= 0 && hotbarY < 16) return col;
        return -1;
    }

    private int thumbHeightPx() {
        int totalRows = (results.size() + GRID_COLS - 1) / GRID_COLS;
        if (totalRows <= 0) return SCROLLBAR_TRACK_HEIGHT;
        return Math.max(12, SCROLLBAR_TRACK_HEIGHT * GRID_ROWS / totalRows);
    }

    private int thumbY() {
        int range = SCROLLBAR_TRACK_HEIGHT - thumbHeightPx();
        int offset = maxScrollRows == 0 ? 0 : range * scrollOffsetRows / maxScrollRows;
        return gridY + 18 + offset;
    }

    private boolean withinScrollbarThumb(double mouseX, double mouseY) {
        int x0 = gridX + IMAGE_WIDTH + 4;
        int y0 = thumbY();
        int h = thumbHeightPx();
        return mouseX >= x0 && mouseX < x0 + SCROLLBAR_WIDTH && mouseY >= y0 && mouseY < y0 + h;
    }

    private void updateScrollFromMouse(double mouseY) {
        int range = SCROLLBAR_TRACK_HEIGHT - thumbHeightPx();
        if (range <= 0 || maxScrollRows == 0) return;
        double trackTop = gridY + 18;
        double frac = (mouseY - trackTop - thumbHeightPx() / 2.0) / range;
        scrollOffsetRows = Math.max(0, Math.min(maxScrollRows, (int) Math.round(frac * maxScrollRows)));
    }

    private void onGroupClicked(CacheSearch.Group group) {
        if (group == armedGroup) {
            armedGroup = null;
            statusLine = "";
            return;
        }
        if (group.fromCommand()) {
            int remaining = cooldownRemaining(group);
            if (remaining > 0) {
                statusLine = group.displayName() + " is on cooldown for " + remaining + "s.";
                return;
            }
        }
        armedGroup = group;
        statusLine = "Pick an empty inventory slot for " + group.displayName() + ".";
    }

    private void commitArmedFetch(int targetSlot) {
        if (armedGroup == null) return;
        LocalPlayer player = Minecraft.getInstance().player;
        ItemStack existing = player != null ? player.getInventory().getItem(targetSlot) : ItemStack.EMPTY;
        if (!existing.isEmpty()) {
            statusLine = "Pick an empty inventory slot.";
            return;
        }
        if (FetchQueue.isQueued(targetSlot)) {
            statusLine = "That slot already has a fetch queued.";
            return;
        }
        int amountWanted = Math.min(itemMaxStackSize(armedGroup.itemId()), armedGroup.totalCount());
        FetchQueue.enqueue(armedGroup, targetSlot, amountWanted);
        statusLine = "Queued " + armedGroup.displayName() + "...";
        armedGroup = null;
    }

    private int cooldownRemaining(CacheSearch.Group group) {
        if (!group.fromCommand() || group.members().isEmpty()) return 0;
        return CommandCooldowns.remainingSeconds(group.members().get(0).source().id);
    }

    private static int itemMaxStackSize(String itemId) {
        var item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(itemId)).orElse(Items.BARRIER);
        return item.getDefaultMaxStackSize();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xC0101010);

        g.blit(RenderPipelines.GUI_TEXTURED, CONTAINER_BACKGROUND, gridX, gridY, 0f, 0f, IMAGE_WIDTH, IMAGE_HEIGHT, 256, 256);

        List<CachedIcon> icons = new ArrayList<>();
        int start = scrollOffsetRows * GRID_COLS;
        int end = Math.min(results.size(), start + GRID_COLS * GRID_ROWS);
        for (int i = start; i < end; i++) {
            CacheSearch.Group group = results.get(i);
            int local = i - start;
            int col = local % GRID_COLS, row = local / GRID_COLS;
            int sx = gridX + 8 + col * SLOT_SIZE;
            int sy = gridY + 18 + row * SLOT_SIZE;
            if (group == armedGroup) {
                g.fill(sx - 1, sy - 1, sx + 17, sy + 17, 0x80FFFF55);
            }
            boolean onCooldown = group.fromCommand() && cooldownRemaining(group) > 0;
            ItemStack display = displayStack(group.itemId(), group.totalCount());
            g.item(display, sx, sy);
            g.itemDecorations(font, display, sx, sy);
            if (onCooldown) {
                g.fill(sx, sy, sx + 16, sy + 16, 0x90101010); // greyed out while a command source is on cooldown
            }
            icons.add(new CachedIcon(group, sx, sy));
        }

        if (maxScrollRows > 0) {
            int trackX = gridX + IMAGE_WIDTH + 4;
            g.fill(trackX, gridY + 18, trackX + SCROLLBAR_WIDTH, gridY + 18 + SCROLLBAR_TRACK_HEIGHT, 0xFF373737);
            int thumbY = thumbY();
            g.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbHeightPx(), 0xFFAAAAAA);
        }

        g.text(font, "Inventory", gridX + 8, gridY + IMAGE_HEIGHT - 94, 0xFF404040);
        LocalPlayer invPlayer = Minecraft.getInstance().player;
        if (invPlayer != null) {
            Inventory inv = invPlayer.getInventory();
            int invY = gridY + GRID_ROWS * SLOT_SIZE + 13;
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    renderInventorySlot(g, inv, row * 9 + col + 9, gridX + 8 + col * SLOT_SIZE, invY + row * SLOT_SIZE);
                }
            }
            for (int col = 0; col < 9; col++) {
                renderInventorySlot(g, inv, col, gridX + 8 + col * SLOT_SIZE, invY + 58);
            }
        }

        g.text(font, resultCountLine, 10, gridY + IMAGE_HEIGHT + 8, 0xFFAAAAAA);
        g.text(font, statusLine, 10, gridY + IMAGE_HEIGHT + 18, 0xFFAAAAAA);

        for (CachedIcon icon : icons) {
            if (mouseX >= icon.x && mouseX < icon.x + 16 && mouseY >= icon.y && mouseY < icon.y + 16) {
                List<Component> tooltip = new ArrayList<>();
                tooltip.add(Component.literal(icon.group.displayName() + " x" + icon.group.totalCount()));
                if (icon.group.fromCommand()) {
                    int remaining = cooldownRemaining(icon.group);
                    String cooldownText = remaining > 0 ? "on cooldown: " + remaining + "s" : "ready";
                    tooltip.add(Component.literal("(command -- " + cooldownText + ")").withStyle(s -> s.withColor(0xAAAAAA)));
                } else {
                    tooltip.add(Component.literal(icon.group.members().size() + " source(s)").withStyle(s -> s.withColor(0xAAAAAA)));
                }
                for (String line : icon.group.componentSummary()) {
                    tooltip.add(Component.literal(line).withStyle(s -> s.withColor(0x55FFFF)));
                }
                g.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
                break;
            }
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private record CachedIcon(CacheSearch.Group group, int x, int y) {}

    /** Real item if the slot holds one; otherwise a washed-out ghost icon while FetchQueue still has something pending for it, or a faint highlight while a group is armed and this slot is a valid (empty, not already queued) target. */
    private void renderInventorySlot(GuiGraphicsExtractor g, Inventory inv, int index, int x, int y) {
        ItemStack real = inv.getItem(index);
        if (!real.isEmpty()) {
            g.item(real, x, y);
            g.itemDecorations(font, real, x, y);
            return;
        }
        ItemStack ghost = FetchQueue.ghostFor(index);
        if (ghost != null) {
            g.item(ghost, x, y);
            g.itemDecorations(font, ghost, x, y);
            g.fill(x, y, x + 16, y + 16, 0x80101010); // washed-out/"pending" look -- see class doc, no real alpha compositing on the item render itself
            return;
        }
        if (armedGroup != null) {
            g.fill(x, y, x + 16, y + 16, 0x5055FF55);
        }
    }

    private static ItemStack displayStack(String itemId, int count) {
        var item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(itemId)).orElse(Items.BARRIER);
        return new ItemStack(item, Math.max(1, count));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
