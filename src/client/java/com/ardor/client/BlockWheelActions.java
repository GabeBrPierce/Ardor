package com.ardor.client;

import com.google.gson.JsonObject;
import com.ardor.game.ActionDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

/**
 * "Break Block" / "Break # of Blocks" from SingleSelectionMode's block sub-wheel -- both just build
 * the same {"action":"mine",...} IR PathfindingController.handleMine already walks-to-and-breaks for
 * the ascii `mine` verb (see AsciiActionCodec.decodeMine), so this reuses that tested walk+break
 * loop wholesale instead of reimplementing reach-checking/pathing here. Built directly as JSON
 * (ActionDispatcher.execute(JsonObject), not the ascii-string path) to avoid round-tripping a real
 * BlockPos through string parsing for no reason.
 */
public final class BlockWheelActions {

    private static final double SINGLE_BLOCK_RADIUS = 1.0;
    private static final double SAME_TYPE_SEARCH_RADIUS = 16.0;

    private BlockWheelActions() {}

    public static void breakOne(BlockPos pos) {
        dispatchMine(pos, pos, 1, SINGLE_BLOCK_RADIUS);
    }

    public static void breakCount(Block block, BlockPos near, int count) {
        dispatchMine(block, near, count, SAME_TYPE_SEARCH_RADIUS);
    }

    private static void dispatchMine(BlockPos exact, BlockPos near, int count, double radius) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        Block block = level.getBlockState(exact).getBlock();
        dispatchMine(block, near, count, radius);
    }

    private static void dispatchMine(Block block, BlockPos near, int count, double radius) {
        JsonObject action = new JsonObject();
        action.addProperty("action", "mine");
        JsonObject blockRef = new JsonObject();
        blockRef.addProperty("id", BuiltInRegistries.BLOCK.getKey(block).toString());
        action.add("block", blockRef);
        action.addProperty("count", count);
        JsonObject origin = new JsonObject();
        origin.addProperty("x", near.getX());
        origin.addProperty("y", near.getY());
        origin.addProperty("z", near.getZ());
        action.add("searchOrigin", origin);
        action.addProperty("searchRadius", radius);
        ActionDispatcher.execute(action);
        StatusIndicator.show("Mining " + count + "x " + block.getName().getString());
    }
}
