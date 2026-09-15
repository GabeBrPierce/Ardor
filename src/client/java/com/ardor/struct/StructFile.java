package com.ardor.struct;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A .struct file: a shareable build (like a Litematica schematic) plus, unlike Litematica, an
 * ordered log of HOW it was built -- where the builder stood, which face they clicked, their
 * look angle, and whether they were sneaking for each block. `blocks` is the static end-state
 * snapshot (used for preview/ghosting and as the source of truth for what to place); `placements`
 * is the build order actually driven at construction time. `placements` is empty for a snapshot
 * imported from a .litematic with no recorded human session behind it -- BuildPlanner fills that
 * gap by synthesizing a plausible order instead, see its own doc for why that's a different,
 * more mechanical thing than a real recording (PlacementRecorder).
 *
 * Plain Gson JSON, not NBT -- matches every other persisted format in this codebase (Macro,
 * RegionProfile). Positions are relative to an arbitrary per-file origin (wherever recording
 * started, or the schematic's own min corner on import) so a file can be built at any location;
 * StructBuilder resolves them against a chosen anchor at build time.
 */
public final class StructFile {

    public String name;
    public String description = "";
    public String author = "";
    public List<String> tags = new ArrayList<>();
    public long timeCreated;
    public boolean synthesizedPlacements; // true once BuildPlanner filled `placements` in, rather than a real recording

    public List<BlockEntry> blocks = new ArrayList<>();
    public List<PlacementEntry> placements = new ArrayList<>();

    public static final class BlockEntry {
        public int x, y, z;
        public String block; // e.g. "minecraft:stone_stairs"
        public Map<String, String> properties = new LinkedHashMap<>();
    }

    public static final class PlacementEntry {
        public int x, y, z; // block position placed
        public String block;
        public Map<String, String> properties = new LinkedHashMap<>();

        public double standX, standY, standZ; // where the player stood, relative to the same origin
        public String face; // Direction name of the neighbor face that was clicked (e.g. "UP")
        public float yaw, pitch;
        public boolean shift;
        public int tickDelta; // ticks since the previous placement, for pacing playback
    }
}
