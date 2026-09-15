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

    /** Returns {x, y} for a widget of width `w` -- wraps to a new row first if it wouldn't fit (and at least one widget is already on the current row, so a single widget wider than maxX-startX still gets placed rather than looping forever). */
    public int[] next(int w) {
        if (x + w > maxX && x > startX) {
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
