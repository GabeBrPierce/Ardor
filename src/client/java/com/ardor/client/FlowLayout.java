package com.ardor.client;

/**
 * "In most of the setting screens the buttons on the right side of the screen become cluttered
 * and go on top of each other, even when full screened. They should wrap automatically." Root
 * cause, confirmed by reading the actual code: TaskPlannerScreen's Y=36 button row placed each
 * button at a hardcoded absolute X (10, 74, 138, 202, 281, 345, 406 -- Run/Cancel/Pause/Auto-Run/
 * Regions/Events/Companion UI), ending around x=496, while Close on the SAME row was placed at
 * `width - 65` assuming the window is always wide enough to fit both without colliding -- untrue
 * at a large GUI Scale (Minecraft's UI coordinate system is in scaled logical pixels, not raw
 * screen pixels, so "full screened" alone doesn't guarantee enough width) or a genuinely narrow
 * window. A plain left-to-right cursor that wraps to a new row instead of overflowing past a max X
 * fixes this generically, for any screen that adopts it, rather than hand-tuning one row's magic
 * numbers.
 */
public final class FlowLayout {

    private final int startX;
    private final int maxX;
    private final int rowHeight;
    private final int gapX;
    private final int gapY;
    private int x;
    private int y;

    public FlowLayout(int startX, int startY, int maxX, int rowHeight, int gapX, int gapY) {
        this.startX = startX;
        this.maxX = maxX;
        this.rowHeight = rowHeight;
        this.gapX = gapX;
        this.gapY = gapY;
        this.x = startX;
        this.y = startY;
    }

    /**
     * Returns {x, y} for a widget of width `w` -- wraps to a new row first if it wouldn't fit at
     * the current x. Wraps even when this is the very first widget on the row (a lone widget placed
     * far enough right by its own maxX/startX gap can still overflow just as easily as a second
     * widget can -- confirmed live: ScriptListScreen's lone "New" button, the only thing ever
     * flowed through its own FlowLayout instance, silently overlapped a fixed-position Help button
     * at narrower widths because the old `x > startX` guard skipped the wrap check entirely for a
     * first/only item). Wrapping only ever resets x back to startX, never loops or recurses, so
     * there's no infinite-loop risk to guard against in the first place -- a widget still too wide
     * even fresh off startX just renders overflowing on its own row, which is the one case wrapping
     * genuinely can't help.
     */
    public int[] next(int w) {
        if (x + w > maxX) {
            x = startX;
            y += rowHeight + gapY;
        }
        int[] pos = {x, y};
        x += w + gapX;
        return pos;
    }

    /** Y just past the bottom of whichever row the cursor is currently on -- for laying out whatever comes after this flow. */
    public int bottom() {
        return y + rowHeight;
    }
}
