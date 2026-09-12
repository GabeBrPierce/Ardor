package com.ardor.container;

import java.util.List;

/** One stack found in a source at last scan time. slot is where within the source this was found (container slot index, or sub-container content index) so a later fetch can re-target it; componentSummary is ComponentSummarizer's human-readable lines (custom name, enchantments, ...), used for the Fetch Items screen's component-level filtering. */
public final class CachedItem {
    public final String sourceId;
    public final int slot;
    public final String itemId;
    public final int count;
    public final String displayName;
    public final List<String> componentSummary;

    public CachedItem(String sourceId, int slot, String itemId, int count, String displayName, List<String> componentSummary) {
        this.sourceId = sourceId;
        this.slot = slot;
        this.itemId = itemId;
        this.count = count;
        this.displayName = displayName;
        this.componentSummary = componentSummary;
    }
}
