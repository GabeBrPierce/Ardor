package com.ardor.client;

/** Shared clamp-and-detect-change arithmetic for mouse-wheel scroll handlers. */
final class ScrollState {
    private ScrollState() {}

    static int scrolled(int offset, int maxOffset, double scrollY, int step) {
        return Math.max(0, Math.min(maxOffset, offset - (int) Math.signum(scrollY) * step));
    }
}
