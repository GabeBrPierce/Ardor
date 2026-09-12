package com.ardor.container;

/**
 * One line of a source's declarative Desired Contents (Physical/Sub container) or Expected
 * Contents (Command) metadata -- a hint about what a source is expected to hold, separate from
 * the live-scanned cache (see ContainerCache). Never blocks saving a source: a source can have
 * an empty desiredContents list.
 *
 * kind=CATEGORY names a whole real creative-mode-tab grouping (category = CreativeModeTab's own
 * display name, e.g. "Food & Drinks" -- see CreativeCategories); kind=ITEM names one specific
 * item id, optionally narrowed by customNameMatch (a case-insensitive substring against the
 * item's custom name component). delta is only meaningful for a COMMAND source's Expected
 * Contents (the user's own example: "/kit food would give me 5 steak, so I'd add item steak with
 * count +5") -- 0/unused for Physical and Sub container sources.
 */
public final class ContentsEntry {

    public enum Kind { CATEGORY, ITEM }

    public Kind kind;
    public String category;
    public String itemId;
    public String customNameMatch;
    public int delta;

    public ContentsEntry() {}
}
