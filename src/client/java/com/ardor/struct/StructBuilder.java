package com.ardor.struct;

import com.ardor.client.StatusIndicator;
import com.ardor.game.GameActionController;
import com.ardor.game.PathfindingController;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Input;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Plays back a StructFile's placement log: walk to the recorded/planned standing spot (reusing
 * PathfindingController.walkThenRun exactly like BreakAreaController does for breaking -- it
 * already does "find a reachable spot near this position," and the recorded standPos IS that
 * position, so a spot search centered on it just confirms/refines it), then dispatch the actual
 * placement through GameActionController's existing "place" verb (same one BreakAreaController's
 * obtainAndPlaceContainer uses) rather than reimplementing item-select/aim/useItemOn here.
 *
 * A missing-material or invalid-placement failure (GameActionController throws) skips that one
 * block and continues -- no auto-gather integration yet (see TODO.md), a run just ends up with
 * gaps where the player didn't have the right block on hand.
 */
public final class StructBuilder {

    private static volatile boolean active;
    private static Deque<StructFile.PlacementEntry> queue = new ArrayDeque<>();
    private static BlockPos anchor;
    private static int totalCount;
    private static int placedCount;

    private StructBuilder() {}

    public static boolean isActive() {
        return active;
    }

    public static void cancel() {
        active = false;
        queue.clear();
    }

    /** anchor is the world position that the struct's relative (0,0,0) maps to. */
    public static void start(StructFile struct, BlockPos anchor) {
        if (active) return;
        java.util.List<StructFile.PlacementEntry> placements = struct.placements.isEmpty()
                ? BuildPlanner.plan(struct.blocks)
                : struct.placements;
        active = true;
        totalCount = placements.size();
        placedCount = 0;
        StructBuilder.anchor = anchor;
        queue = new ArrayDeque<>(placements);
        StatusIndicator.show("Building '" + struct.name + "' (" + totalCount + " block(s))...");
        next();
    }

    private static void next() {
        if (!active) return;
        StructFile.PlacementEntry entry = queue.poll();
        if (entry == null) {
            active = false;
            StatusIndicator.show("Built " + placedCount + "/" + totalCount + " block(s).");
            return;
        }

        BlockPos target = anchor.offset(entry.x, entry.y, entry.z);
        BlockPos standWorld = BlockPos.containing(
                anchor.getX() + entry.standX, anchor.getY() + entry.standY, anchor.getZ() + entry.standZ);

        PathfindingController.walkThenRun(standWorld, "couldn't reach " + standWorld + " to place a block",
                () -> place(entry, target),
                failReason -> {
                    System.err.println("[ardor] struct build: skipping " + target + ": " + failReason);
                    next();
                });
    }

    private static void place(StructFile.PlacementEntry entry, BlockPos target) {
        var player = Minecraft.getInstance().player;
        Input original = player.input.keyPresses;
        if (entry.shift) {
            player.input.keyPresses = new Input(false, false, false, false, false, true, false);
        }
        try {
            GameActionController.dispatch(placeAction(entry, target));
            placedCount++;
        } catch (RuntimeException e) {
            System.err.println("[ardor] struct build: couldn't place " + entry.block + " at " + target + ": " + e.getMessage());
        } finally {
            player.input.keyPresses = original;
        }
        next();
    }

    private static JsonObject placeAction(StructFile.PlacementEntry entry, BlockPos target) {
        JsonObject action = new JsonObject();
        action.addProperty("action", "place");
        JsonObject block = new JsonObject();
        block.addProperty("id", entry.block);
        action.add("block", block);
        JsonObject position = new JsonObject();
        position.addProperty("x", target.getX());
        position.addProperty("y", target.getY());
        position.addProperty("z", target.getZ());
        action.add("position", position);
        action.addProperty("facing", entry.face);
        return action;
    }
}
