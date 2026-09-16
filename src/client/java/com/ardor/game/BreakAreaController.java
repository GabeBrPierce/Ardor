package com.ardor.game;

import com.ardor.client.StatusIndicator;
import com.ardor.region.RegionManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Break Blocks Within" from the Area Selection follow-up wheel -- enumerates every breakable,
 * non-air block in the given AABB (capped at MAX_BLOCKS, just a hard limit so a huge selection
 * can't hang the client scanning or queue an unreasonable job) and breaks them one at a time.
 * Walking to each block reuses PathfindingController.walkThenRun (the same shared walk state
 * mine/goto/follow all drive -- it already finds a reachable standable spot adjacent to whatever
 * target position it's given, same as mine's own candidate search); breaking it reuses a fresh
 * BlockBreaker instance (its public breakBlock/isActive/consumeLastFailure API is exactly the
 * "walk there first, then call this" shape mine already uses internally). Tool-tier pre-flight
 * calls PathfindingController.ensureToolFor once per block, right before walking to it -- not
 * batched by type up front, since a huge mixed-type area could otherwise front-load a long chain
 * of tool/material gathering before breaking a single block; already gated by the region's
 * excludeImplicitItemRetrieval/excludeImplicitItemManufacturing flags, same as mine.
 *
 * Capacity is checked up front by the caller (hasRoughCapacityFor); if short, obtainAndPlace
 * ("look for a chest, else craft one from planks/logs, then place it near the site") gets the bot
 * a real place to dump into, and start()'s optional dumpTarget periodically empties the inventory
 * there mid-run (INVENTORY_FREE_SLOTS_LOW_WATERMARK) the same way shaft/hole digging already dump
 * into a container -- see PathfindingController.dumpInventoryIntoContainer.
 */
public final class BreakAreaController {

    private static final int MAX_BLOCKS = 2048;
    private static final int INVENTORY_FREE_SLOTS_LOW_WATERMARK = 3; // dump once free slots drop to this or fewer
    private static final int PLACEMENT_SEARCH_RADIUS = 12;
    private static final int PLACEMENT_SEARCH_Y_SPAN = 4; // +/- this many blocks around the area's own center Y

    private static volatile boolean active;
    private static Deque<BlockPos> queue = new ArrayDeque<>();
    private static BlockBreaker breaker;
    private static int totalCount;
    private static int brokenCount;
    private static BlockPos dumpTarget;
    private static BlockPos returnPos;
    private static BlockPos currentPos; // the one block in flight -- poll()'d off queue but not yet broken, so a crash mid-break doesn't lose it from the persisted resume state

    private BreakAreaController() {}

    // ------------------------------------------------------------------ resume / persistence

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private record Pos(int x, int y, int z) {
        static Pos of(BlockPos p) { return new Pos(p.getX(), p.getY(), p.getZ()); }
        BlockPos toBlockPos() { return new BlockPos(x, y, z); }
    }

    private record ResumeState(String profileKey, int totalCount, int brokenCount, List<Pos> remaining, Pos dumpTarget, Pos returnPos) {}

    private static Path resumePath() {
        return FabricLoader.getInstance().getConfigDir().resolve("ardor-break-area-resume.json");
    }

    private static void persist() {
        List<Pos> remaining = new ArrayList<>();
        if (currentPos != null) remaining.add(Pos.of(currentPos));
        for (BlockPos p : queue) remaining.add(Pos.of(p));
        ResumeState state = new ResumeState(RegionManager.currentProfileKey(), totalCount, brokenCount, remaining,
                dumpTarget != null ? Pos.of(dumpTarget) : null, returnPos != null ? Pos.of(returnPos) : null);
        try {
            Files.writeString(resumePath(), GSON.toJson(state));
        } catch (IOException e) {
            System.err.println("[ardor] break area: failed to persist resume state: " + e);
        }
    }

    private static void clearPersisted() {
        try {
            Files.deleteIfExists(resumePath());
        } catch (IOException e) {
            System.err.println("[ardor] break area: failed to clear resume state: " + e);
        }
    }

    private static ResumeState loadPersisted() {
        Path p = resumePath();
        if (!Files.exists(p)) return null;
        try {
            return GSON.fromJson(Files.readString(p), ResumeState.class);
        } catch (IOException e) {
            System.err.println("[ardor] break area: failed to load resume state: " + e);
            return null;
        }
    }

    /** For the "found interrupted work" world-join check and the Resume Interrupted Work screen. */
    public static boolean hasResumable() {
        if (active) return false;
        ResumeState state = loadPersisted();
        return state != null && state.profileKey().equals(RegionManager.currentProfileKey()) && !state.remaining().isEmpty();
    }

    public static String resumableSummary() {
        ResumeState state = loadPersisted();
        if (state == null) return null;
        return "Break Blocks Within: " + state.brokenCount() + "/" + state.totalCount() + " broken, "
                + state.remaining().size() + " left";
    }

    public static void discardResumable() {
        clearPersisted();
    }

    /** Picks the queue/dump target/return position back up from the last persisted state -- see start()'s counterpart. */
    public static void resume() {
        if (active) return;
        ResumeState state = loadPersisted();
        if (state == null || !state.profileKey().equals(RegionManager.currentProfileKey()) || state.remaining().isEmpty()) return;
        active = true;
        totalCount = state.totalCount();
        brokenCount = state.brokenCount();
        queue = new ArrayDeque<>(state.remaining().stream().map(Pos::toBlockPos).toList());
        currentPos = null;
        breaker = new BlockBreaker();
        dumpTarget = state.dumpTarget() != null ? state.dumpTarget().toBlockPos() : null;
        returnPos = state.returnPos() != null ? state.returnPos().toBlockPos() : null;
        StatusIndicator.show("Resuming break area: " + brokenCount + "/" + totalCount + " broken, " + queue.size() + " left.");
        next();
    }

    /**
     * Wraps a re-entry point (called from BlockBreaker's/PathExecutor's/Baritone's own tick loops,
     * none of which know about this class's state) so an exception stops this run cleanly and
     * resumably instead of either hanging forever (baritoneNavTick's own outer catch clears ITS
     * goal but never touches BreakAreaController.active, leaving this class thinking it's still
     * active with nothing left driving it) or escaping into a caller with no catch at all and
     * crashing the client (BlockBreaker's own tick loop -- confirmed via reading it -- has none).
     */
    private static void guarded(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            System.err.println("[ardor] break area: stopped by error: " + e);
            active = false;
            if (breaker != null) breaker.cancel();
            persist();
            StatusIndicator.show("Break area stopped by an error (" + brokenCount + "/" + totalCount
                    + " broken) -- " + e.getMessage() + ". Resume it from the Ardor menu to continue.");
        }
    }

    public static boolean isActive() {
        return active;
    }

    /** For QuestTrackerOverlay -- "it should ALWAYS display what's currently happening," including runs like this one that never go through TaskOrchestrator at all. {brokenCount, totalCount}. */
    public static int totalCount() {
        return totalCount;
    }

    public static int brokenCount() {
        return brokenCount;
    }

    /**
     * Non-air, breakable (getDestroySpeed >= 0 -- bedrock/barrier/etc. return negative) blocks
     * within box, capped at MAX_BLOCKS. Used both for the real run and for the up-front capacity
     * check.
     *
     * "We should NEVER try and dig straight down -- that was the first thing it did. We should
     * excavate horizontally moving downwards, leaving stairs ascending to the top." Confirmed
     * real: this used to just hand back BlockPos.betweenClosed's own raw scan order, with nothing
     * ordering it by safety at all -- whatever that iteration order happened to put first (a
     * column at one corner) is what got broken first, in order, which reads as "dug straight down"
     * whenever that happened to be a vertical run. Now ordered top layer to bottom layer, each
     * layer swept horizontally in full before the next one down starts -- since walkThenRun/
     * queue-order IS execution order, this directly means the bot is never digging through
     * un-cleared layers above to reach a deeper target; every layer above whatever it's currently
     * breaking is already open air. A straight staircase (reservedStaircaseFloor) hugging one edge
     * is reserved (skipped, left solid) so a walkable ramp back to the top survives the excavation
     * instead of leaving a walled-in pit.
     */
    public static List<BlockPos> enumerate(AABB box, Level level) {
        BlockPos min = BlockPos.containing(box.minX, box.minY, box.minZ);
        BlockPos max = BlockPos.containing(box.maxX, box.maxY, box.maxZ);
        Set<BlockPos> reservedFloor = reservedStaircaseFloor(min, max);

        // "We're moving very randomly -- I want it to follow [a continuous back-and-forth sweep]."
        // Real gap, not just a cosmetic one: the old version always swept Z from min to max for
        // EVERY x column, so after finishing one column it jumped all the way back across to the
        // start of the next -- a plain raster scan, not a snake. walkThenRun/queue order IS
        // execution/walking order (see this method's own doc), so that raster jump is exactly what
        // read as "random" movement. Now a real boustrophedon: alternates Z direction each time X
        // increments, so the walking path is one continuous back-and-forth sweep per layer with no
        // backtracking, matching a real "mow the lawn" pattern.
        List<BlockPos> found = new ArrayList<>();
        for (int y = max.getY(); y >= min.getY(); y--) {
            boolean forward = true;
            for (int x = min.getX(); x <= max.getX(); x++) {
                int zStart = forward ? min.getZ() : max.getZ();
                int zEnd = forward ? max.getZ() : min.getZ();
                int zStep = forward ? 1 : -1;
                for (int z = zStart; forward ? z <= zEnd : z >= zEnd; z += zStep) {
                    if (found.size() >= MAX_BLOCKS) return found;
                    BlockPos pos = new BlockPos(x, y, z);
                    if (reservedFloor.contains(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir() || state.getDestroySpeed(level, pos) < 0) continue;
                    found.add(pos);
                }
                forward = !forward;
            }
        }
        return found;
    }

    /**
     * A straight staircase hugging the min-Z edge, descending one Y level per block moved along
     * +X, starting one level below the very top (the top layer itself is the starting surface, it
     * needs no floor reserved under it). Reserves ONLY the floor (support) block under each step --
     * the step tile itself and the headroom above it get cleared normally along with everything
     * else in the volume, so what's left behind is a walkable ramp, not a separately-carved one.
     * Capped by whichever runs out first, the area's height or its width -- a selection much
     * taller than it is wide only gets a partial ramp; a genuinely honest limitation of a simple,
     * unverified-without-a-live-test approach rather than a claim of a universally correct one
     * (see TODO.md).
     */
    private static Set<BlockPos> reservedStaircaseFloor(BlockPos min, BlockPos max) {
        Set<BlockPos> floor = new HashSet<>();
        int height = max.getY() - min.getY() + 1;
        int width = max.getX() - min.getX() + 1;
        int steps = Math.min(height - 1, width);
        for (int i = 0; i < steps; i++) {
            int stepFloorY = max.getY() - 1 - i;
            int stepX = min.getX() + i;
            floor.add(new BlockPos(stepX, stepFloorY, min.getZ()));
        }
        return floor;
    }

    /** Rough go/no-go: each free inventory slot could hold up to a full stack, so freeSlots*64 is an optimistic upper bound on how many single-block drops fit -- not exact (real stack sizes vary by block), but enough to catch "this is obviously more than I can carry." */
    public static boolean hasRoughCapacityFor(int blockCount) {
        var player = Minecraft.getInstance().player;
        if (player == null) return false;
        return (long) freeSlots(player.getInventory()) * 64 >= blockCount;
    }

    private static int freeSlots(Inventory inv) {
        int free = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) free++;
        }
        return free;
    }

    public static void start(List<BlockPos> blocks) {
        start(blocks, null);
    }

    public static void start(List<BlockPos> blocks, BlockPos dumpTargetPos) {
        if (active) return;
        active = true;
        totalCount = blocks.size();
        brokenCount = 0;
        queue = new ArrayDeque<>(blocks);
        currentPos = null;
        breaker = new BlockBreaker();
        dumpTarget = dumpTargetPos;
        var player = Minecraft.getInstance().player;
        returnPos = player != null ? player.blockPosition() : null; // "pathfind back to the place where the request to dig was" once done -- see finishRun's own doc
        StatusIndicator.show("Breaking " + totalCount + " block(s)...");
        persist();
        next();
    }

    public static void cancel() {
        active = false;
        queue.clear();
        dumpTarget = null;
        returnPos = null;
        if (breaker != null) breaker.cancel();
    }

    /**
     * "The bot should take the steps to obtain a container... and place it near the break all
     * site" -- look for an existing chest first, else craft one (PathfindingController.
     * ensureChests, reusing the exact log-mine/plank-craft chain tool-crafting-from-scratch
     * already uses), then find a clear, ground-supported spot near the area and place it (walking
     * there via walkThenRun, placing via the existing `place` verb). onPlaced receives the placed
     * position (for start()'s dumpTarget) or null if a container couldn't be obtained/placed at
     * all -- callers decide what "couldn't get a container" means for them (cancel, warn, proceed
     * without one).
     */
    public static void obtainAndPlaceContainer(AABB near, java.util.function.Consumer<BlockPos> onPlaced) {
        PathfindingController.ensureChests(1, () -> {
            BlockPos spot = findPlacementSpot(near);
            if (spot == null) {
                StatusIndicator.show("Couldn't find a clear spot to place a container nearby.");
                onPlaced.accept(null);
                return;
            }
            PathfindingController.walkThenRun(spot, "couldn't reach " + spot + " to place a container",
                    () -> {
                        placeChestAt(spot);
                        onPlaced.accept(spot);
                    },
                    failReason -> {
                        StatusIndicator.show("Couldn't reach a spot to place a container: " + failReason);
                        onPlaced.accept(null);
                    });
        });
    }

    private static void placeChestAt(BlockPos pos) {
        JsonObject action = new JsonObject();
        action.addProperty("action", "place");
        JsonObject block = new JsonObject();
        block.addProperty("id", "minecraft:chest");
        action.add("block", block);
        JsonObject position = new JsonObject();
        position.addProperty("x", pos.getX());
        position.addProperty("y", pos.getY());
        position.addProperty("z", pos.getZ());
        action.add("position", position);
        action.addProperty("facing", "up");
        GameActionController.dispatch(action);
    }

    /** First air block with solid ground beneath it and air above (room for the chest to open), scanning outward near the area's own center. Simple, capped brute-force search -- not exhaustive, just enough to usually find a real spot. */
    private static BlockPos findPlacementSpot(AABB near) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return null;
        Vec3 centerVec = near.getCenter();
        BlockPos center = BlockPos.containing(centerVec.x, centerVec.y, centerVec.z);
        for (int dx = -PLACEMENT_SEARCH_RADIUS; dx <= PLACEMENT_SEARCH_RADIUS; dx++) {
            for (int dz = -PLACEMENT_SEARCH_RADIUS; dz <= PLACEMENT_SEARCH_RADIUS; dz++) {
                for (int dy = -PLACEMENT_SEARCH_Y_SPAN; dy <= PLACEMENT_SEARCH_Y_SPAN; dy++) {
                    BlockPos candidate = center.offset(dx, dy, dz);
                    if (isValidChestSpot(level, candidate)) return candidate;
                }
            }
        }
        return null;
    }

    private static boolean isValidChestSpot(Level level, BlockPos pos) {
        return level.getBlockState(pos).isAir()
                && level.getBlockState(pos.above()).isAir()
                && !level.getBlockState(pos.below()).isAir();
    }

    private static void next() {
        guarded(BreakAreaController::nextInner);
    }

    private static void nextInner() {
        if (!active) return;
        BlockPos pos = queue.poll();
        currentPos = pos;
        if (pos == null) {
            active = false;
            clearPersisted(); // the whole job finished on its own -- nothing left to resume
            StatusIndicator.show("Broke " + brokenCount + "/" + totalCount + " block(s).");
            finishRun();
            return;
        }

        Level level = Minecraft.getInstance().level;
        if (level == null || level.getBlockState(pos).isAir()) {
            next(); // already gone (e.g. broke as a side effect of an earlier block in this run) -- skip and continue
            return;
        }
        Block type = level.getBlockState(pos).getBlock();
        PathfindingController.ensureToolFor(type, () -> guarded(() -> walkAndBreak(pos)));
    }

    /**
     * "Pathfind back to where the dig request was made, or to the chest we're using -- and if we
     * can't find a way back up ourselves, use the stone we mined to build a stairway." Prefers the
     * dump chest (the actual place materials need to end up) over the plain origin point when both
     * are available. The "build a stairway if stuck" half needs no separate implementation: Baritone
     * already only places blocks (pillaring) when a normal walking route doesn't exist, and
     * BaritoneNav.configure now stocks cobblestone/cobbled deepslate -- the real drops from mining
     * stone/deepslate -- as acceptable pillaring material alongside dirt, so this ONE walkThenRun
     * call gets both "walk back" and "build a way up out of a deep pit if walking alone can't" for
     * free, reusing Baritone's own path search rather than a hand-rolled staircase builder.
     */
    private static void finishRun() {
        BlockPos target = dumpTarget != null ? dumpTarget : returnPos;
        if (target == null) return;
        StatusIndicator.show("Heading back to " + (dumpTarget != null ? "the storage container" : "where this started") + "...");
        PathfindingController.walkThenRun(target, "couldn't path back to " + target + " after breaking the area",
                () -> StatusIndicator.show("Back."),
                failReason -> StatusIndicator.show("Finished breaking, but couldn't path back: " + failReason));
    }

    private static void walkAndBreak(BlockPos pos) {
        PathfindingController.walkThenRun(pos, "couldn't reach " + pos + " while breaking area",
                () -> breaker.breakBlock(pos, () -> guarded(() -> {
                    brokenCount++;
                    currentPos = null;
                    persist();
                    maybeDumpThenContinue();
                })),
                failReason -> {
                    System.err.println("[ardor] break area: skipping " + pos + ": " + failReason);
                    next();
                });
    }

    /**
     * Dumping walks via PathExecutor (PathfindingController.dumpInventoryIntoContainer's own
     * pathTo), a different movement system from this class's own Baritone-driven walkThenRun --
     * waits for the dump's onDone before calling next() so the two never actually drive movement
     * at the same time, just one after the other.
     */
    private static void maybeDumpThenContinue() {
        var player = Minecraft.getInstance().player;
        if (dumpTarget != null && player != null && freeSlots(player.getInventory()) <= INVENTORY_FREE_SLOTS_LOW_WATERMARK) {
            StatusIndicator.show("Inventory getting full -- dumping into the container.");
            PathfindingController.dumpInventoryIntoContainer(dumpTarget, BreakAreaController::next);
        } else {
            next();
        }
    }
}
