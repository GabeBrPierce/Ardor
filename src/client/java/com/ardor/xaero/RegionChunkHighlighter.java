package com.ardor.xaero;

import com.ardor.region.Region;
import com.ardor.region.RegionManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.map.highlight.ChunkHighlighter;

import java.util.List;

/**
 * Draws our own regions (RegionManager) as colored overlays on Xaero's real in-game map --
 * verified via javap disassembly of the actual xaeroworldmap-fabric-26.1.2 jar, not assumed:
 * ChunkHighlighter/AbstractHighlighter's public shape, and TestHighlighter (Xaero's own shipped
 * reference implementation) for the exact resultStore/color-array contract, which this class
 * follows closely (see getColors below).
 *
 * Region has no dimension field (see Region.java) -- this codebase's regions are already
 * dimension-agnostic, so this highlighter applies the same regions regardless of which
 * ResourceKey<Level> Xaero is currently asking about, matching that existing data model exactly
 * rather than inventing a dimension concept regions don't actually have.
 *
 * coveringOutsideDiscovered=true (matches TestHighlighter's own choice): a region should be
 * visible on the map immediately once defined, even in fog-of-war the player/bot hasn't
 * physically explored yet -- that's more useful for "here's a marked area" than only showing up
 * once someone happens to walk there.
 */
public final class RegionChunkHighlighter extends ChunkHighlighter {

    // ARGB, semi-transparent cyan-blue -- distinct from vanilla terrain colors, reads clearly as
    // "a marked area" without obscuring the terrain underneath.
    private static final int COLOR = 0x664ac8ff;

    public RegionChunkHighlighter() {
        super(true);
    }

    @Override
    public int calculateRegionHash(ResourceKey<Level> dimension, int regionX, int regionZ) {
        // Deliberately a hash of the WHOLE region list, not scoped to just this map-region-file's
        // area -- getting the exact region-file block-scale right wasn't confirmed from the
        // disassembly, and a hash that's "too broad" only costs some extra re-renders on change,
        // while a hash that's too narrow could leave Xaero's cache stale after an edit. Correctness
        // over optimality for this first pass.
        int hash = 0;
        for (Region r : regions()) {
            hash = hash * 31 + r.name.hashCode();
            if (!r.isGlobal()) {
                hash = hash * 31 + r.minX * 73856093 ^ r.minZ * 19349663 ^ r.maxX * 83492791 ^ r.maxZ;
            }
        }
        return hash;
    }

    @Override
    public boolean regionHasHighlights(ResourceKey<Level> dimension, int regionX, int regionZ) {
        // Same simplification as calculateRegionHash -- always true, correctness over a coarse
        // pre-check whose exact coordinate scale wasn't confirmed. chunkIsHighlit below does the
        // real per-chunk filtering; Xaero's own TestHighlighter reference does the same thing.
        return true;
    }

    @Override
    public boolean chunkIsHighlit(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return regionAt(chunkX, chunkZ) != null;
    }

    @Override
    protected int[] getColors(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        if (!chunkIsHighlit(dimension, chunkX, chunkZ)) return null;
        // Solid color at every resultStore slot TestHighlighter's own reference implementation
        // uses (indices 0-3) -- Xaero's blending logic reads these per-corner/side to fade between
        // highlighted and unhighlighted neighbors; setting them all equal gives a flat, solid fill
        // rather than a graduated blend, which is a simpler and still entirely correct rendering
        // for a real rectangular region's hard edges.
        resultStore[0] = COLOR;
        resultStore[1] = COLOR;
        resultStore[2] = COLOR;
        resultStore[3] = COLOR;
        return resultStore;
    }

    @Override
    public Component getChunkHighlightSubtleTooltip(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        Region r = regionAt(chunkX, chunkZ);
        return Component.literal(r != null ? r.name : "");
    }

    @Override
    public Component getChunkHighlightBluntTooltip(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        Region r = regionAt(chunkX, chunkZ);
        return Component.literal(r != null ? "Region: " + r.name : "");
    }

    @Override
    public void addMinimapBlockHighlightTooltips(List<Component> tooltips, ResourceKey<Level> dimension, int blockX, int blockZ, int y) {
        Region r = regionAt(blockX >> 4, blockZ >> 4);
        if (r != null) tooltips.add(Component.literal("Region: " + r.name));
    }

    private Region regionAt(int chunkX, int chunkZ) {
        int blockX = (chunkX << 4) + 8, blockZ = (chunkZ << 4) + 8; // chunk center, avoids edge-of-chunk rounding ambiguity
        for (Region r : regions()) {
            if (r.isGlobal()) continue; // "global" covers everything -- never worth drawing as an overlay
            if (blockX >= r.minX && blockX <= r.maxX && blockZ >= r.minZ && blockZ <= r.maxZ) return r;
        }
        return null;
    }

    private java.util.Collection<Region> regions() {
        return RegionManager.get().currentProfile().regions.values();
    }
}
