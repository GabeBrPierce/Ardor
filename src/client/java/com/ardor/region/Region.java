package com.ardor.region;

import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A named cuboid area within a RegionProfile. "global" is the implicit root
 * region of every profile: unbounded (min/max both null), no parent, always
 * matches. Every other region has real corners and, optionally, a parent
 * region name -- a region with no parent is a direct child of "global".
 *
 * eventTasks maps an event id (see event/FabricEventCatalog and
 * event/DomainEvents) to the free-text goal that gets planned and pushed to
 * the front of TaskRunner when that event fires while the player is inside
 * this region (or a descendant that doesn't override that same event id --
 * see RegionManager.resolveEventTask).
 */
public final class Region {
    public String name;
    public String parent;
    public Integer minX, minY, minZ, maxX, maxY, maxZ;
    public Map<String, String> eventTasks = new LinkedHashMap<>();

    // Item-fetch scoping flags -- see RegionManager.hasFlag for how these are resolved (OR'd up
    // the whole region -> parent -> ... -> global chain, unlike eventTasks' "nearest wins": a
    // plain boolean has no "unset" state, so OR-ing is what lets flagging one broad parent region
    // apply to everything nested inside it without re-flagging every child).
    public boolean excludeItemSources; // AutoSourceRecorder won't auto-register physical containers/cauldrons here
    public boolean excludeImplicitItemRetrieval; // PathfindingController.ensureToolFor won't search nearby containers for an existing tool here
    public boolean excludeImplicitItemManufacturing; // PathfindingController.ensureToolFor won't craft/smelt a tool from scratch here

    public Region() {}

    public Region(String name, String parent, BlockPos a, BlockPos b) {
        this.name = name;
        this.parent = parent;
        setBounds(a, b);
    }

    public void setBounds(BlockPos a, BlockPos b) {
        this.minX = Math.min(a.getX(), b.getX());
        this.minY = Math.min(a.getY(), b.getY());
        this.minZ = Math.min(a.getZ(), b.getZ());
        this.maxX = Math.max(a.getX(), b.getX());
        this.maxY = Math.max(a.getY(), b.getY());
        this.maxZ = Math.max(a.getZ(), b.getZ());
    }

    public boolean isGlobal() {
        return minX == null;
    }

    public boolean contains(BlockPos pos) {
        if (isGlobal()) return true;
        return pos.getX() >= minX && pos.getX() <= maxX
                && pos.getY() >= minY && pos.getY() <= maxY
                && pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }

    /** Volume, used to break ties between overlapping sibling regions (smaller = more specific) when depth alone doesn't decide it. Long.MAX_VALUE for "global" so it never wins over a real region. */
    public long volume() {
        if (isGlobal()) return Long.MAX_VALUE;
        return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }
}
