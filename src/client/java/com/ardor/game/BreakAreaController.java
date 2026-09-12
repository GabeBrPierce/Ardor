package com.ardor.game;

import com.ardor.client.StatusIndicator;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

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

    private BreakAreaController() {}

    public static boolean isActive() {
        return active;
    }

    /** Non-air, breakable (getDestroySpeed >= 0 -- bedrock/barrier/etc. return negative) blocks within box, capped at MAX_BLOCKS. Used both for the real run and for the up-front capacity check. */
    public static List<BlockPos> enumerate(AABB box, Level level) {
        BlockPos min = BlockPos.containing(box.minX, box.minY, box.minZ);
        BlockPos max = BlockPos.containing(box.maxX, box.maxY, box.maxZ);
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (found.size() >= MAX_BLOCKS) break;
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.getDestroySpeed(level, pos) < 0) continue;
            found.add(pos.immutable());
        }
        return found;
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
        breaker = new BlockBreaker();
        dumpTarget = dumpTargetPos;
        StatusIndicator.show("Breaking " + totalCount + " block(s)...");
        next();
    }

    public static void cancel() {
        active = false;
        queue.clear();
        dumpTarget = null;
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
        if (!active) return;
        BlockPos pos = queue.poll();
        if (pos == null) {
            active = false;
            StatusIndicator.show("Broke " + brokenCount + "/" + totalCount + " block(s).");
            return;
        }

        Level level = Minecraft.getInstance().level;
        if (level == null || level.getBlockState(pos).isAir()) {
            next(); // already gone (e.g. broke as a side effect of an earlier block in this run) -- skip and continue
            return;
        }
        Block type = level.getBlockState(pos).getBlock();
        PathfindingController.ensureToolFor(type, () -> walkAndBreak(pos));
    }

    private static void walkAndBreak(BlockPos pos) {
        PathfindingController.walkThenRun(pos, "couldn't reach " + pos + " while breaking area",
                () -> breaker.breakBlock(pos, () -> {
                    brokenCount++;
                    maybeDumpThenContinue();
                }),
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
