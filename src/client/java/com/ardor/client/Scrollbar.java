package com.ardor.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Reusable vertical scrollbar (track + thumb) for the right edge of a scrollable row list --
 * mouse-wheel scrolling already works on every list screen in this codebase (ScrollState), this
 * just adds a visible, click-to-jump bar alongside it. Purely mechanical: render() draws it,
 * clickedOffset() turns a click inside the track into a new offset -- the caller (a Screen) owns
 * the real offset field and calls rebuildAllWidgets() itself, same "screen owns state, this is
 * just the view" split ScrollState/every other custom-drawn element here already uses. No drag
 * support -- click-to-jump is enough to make the bar functional, not just decorative, without
 * every list screen also needing to plumb mouseDragged.
 */
final class Scrollbar {

    private static final int WIDTH = 6;
    private static final int MIN_THUMB_H = 10;
    private static final int TRACK_COLOR = 0xFF303030;
    private static final int THUMB_COLOR = 0xFF808080;

    private Scrollbar() {}

    static boolean needed(int total, int visible) {
        return total > visible;
    }

    static void render(GuiGraphicsExtractor g, int x, int y, int height, int total, int visible, int offset) {
        g.fill(x, y, x + WIDTH, y + height, TRACK_COLOR);
        int maxOffset = Math.max(1, total - visible);
        int thumbH = Math.max(MIN_THUMB_H, Math.min(height, height * visible / total));
        int thumbY = y + (height - thumbH) * Math.min(offset, maxOffset) / maxOffset;
        g.fill(x, thumbY, x + WIDTH, thumbY + thumbH, THUMB_COLOR);
    }

    /** New scroll offset for a click at (mouseX, mouseY), or -1 if outside the track's bounds. */
    static int clickedOffset(double mouseX, double mouseY, int x, int y, int height, int total, int visible) {
        if (mouseX < x || mouseX > x + WIDTH || mouseY < y || mouseY > y + height) return -1;
        int maxOffset = Math.max(0, total - visible);
        if (maxOffset == 0) return 0;
        double frac = (mouseY - y) / height;
        return (int) Math.round(Math.max(0, Math.min(1, frac)) * maxOffset);
    }
}
