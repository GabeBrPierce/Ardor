package com.ardor.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * "Show on top of the screen a small overlay of where the hologram is, formatted like WAILA" --
 * a compact icon + label box, top-center. Serves BOTH SingleSelectionMode (icon + entity/container/
 * hologram lines) and AreaSelectionMode (size + first-corner/center lines, no icon) -- the two are
 * mutually exclusive (starting one stops the other), so whichever is actually active supplies this
 * box's content; nothing renders while neither is. Purely a reader of both controllers' own
 * overlayLines()/overlayIcon() -- no logic of its own, same "renderer reads a controller's state"
 * split RegionRenderer/PingKey already established. Grows to multiple lines for a container (name,
 * contents summary, expected-items count) -- see SingleSelectionMode.buildContainerOverlayLines().
 *
 * HudElementRegistry (not the older HudRenderCallback, which doesn't exist in this Fabric API
 * version -- confirmed via jar inspection, this version's HUD rendering was rebuilt into a named
 * layer/element registry) lets us add a layer without needing to reimplement vanilla's own HUD.
 */
public final class SingleSelectionOverlay {

    private static final int TOP_MARGIN = 4;
    private static final int LINE_H = 12;
    private static final int PADDING_V = 4;
    private static final int ICON_W = 20;

    private SingleSelectionOverlay() {}

    public static void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("ardor", "single_selection_overlay"),
                SingleSelectionOverlay::render);
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker tracker) {
        // Single Selection and Area Selection are mutually exclusive (starting one stops the
        // other, see both classes' start()) -- whichever is actually active supplies this box's
        // lines, same WAILA-style rendering either way. "The area selector needs to show the same
        // kind of WAILA display Single Selection does."
        List<String> lines = SingleSelectionMode.overlayLines();
        boolean isAreaSelection = lines.isEmpty();
        if (isAreaSelection) lines = AreaSelectionMode.overlayLines();
        if (lines.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        ItemStack icon = isAreaSelection ? ItemStack.EMPTY : SingleSelectionMode.overlayIcon();
        boolean hasIcon = !icon.isEmpty();
        int screenW = mc.getWindow().getGuiScaledWidth();

        int textW = lines.stream().mapToInt(font::width).max().orElse(0);
        int boxW = textW + (hasIcon ? ICON_W : 0) + 12;
        int boxH = lines.size() * LINE_H + PADDING_V * 2;
        int x = screenW / 2 - boxW / 2;
        int y = TOP_MARGIN;

        g.fill(x, y, x + boxW, y + boxH, 0xC0101010);

        int textX = x + 6;
        if (hasIcon) {
            g.item(icon, x + 4, y + boxH / 2 - 8);
            textX = x + 4 + ICON_W;
        }
        int textY = y + PADDING_V;
        for (String line : lines) {
            g.text(font, line, textX, textY, 0xFFFFFFFF);
            textY += LINE_H;
        }
    }
}
