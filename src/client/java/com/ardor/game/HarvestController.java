package com.ardor.game;

import com.ardor.client.StatusIndicator;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * "Harvest One" / "Harvest %" / "Tend Field" from SingleSelectionMode's block sub-wheel.
 *
 * Harvest rules, as specified: a fully-grown wheat/carrots/potatoes/beetroot/nether wart plant is
 * broken and immediately replanted (a real place action, not a client-side fake -- see replant());
 * melon and pumpkin FRUIT blocks are just broken, never replanted (the stem regrows a new one on its
 * own, and stems themselves are never touched here); sugar cane is only "fully grown" at 3+ stacked
 * blocks, and only the TOPMOST block of that stack is broken, leaving the rest standing so it
 * regrows the third block over time -- the standard sustainable sugar-cane-farm technique. (The
 * user's own phrasing here -- "we break the second one up but leave the middle one" -- was
 * self-contradictory for a 3-tall stack: breaking anything but the top always also breaks everything
 * above it, since each block depends on the one below for support. Read as "break the top, leave the
 * rest" -- the only reading consistent with both real sugar cane mechanics and "leave the middle
 * one." Flagged here, not silently guessed past, in case that's not what was meant.)
 *
 * Real, honest limitation: harvesting uses BlockBreaker directly (for its onDone callback, needed to
 * sequence break-then-replant) rather than the walk-there-first "mine" verb BlockWheelActions reuses
 * for plain block breaking -- so unlike Break Block/Break # of Blocks, nothing here walks to a crop
 * that's out of reach first. Tend Field is a fixed-radius re-scan around wherever it was started, not
 * a real "walk the whole field" loop, for the same reason -- both real gaps, not hidden, see TODO.md.
 */
public final class HarvestController {

    public enum CropKind { WHEAT_FAMILY, NETHER_WART, MELON_PUMPKIN, SUGAR_CANE }

    private static final int MIN_SUGAR_CANE_HEIGHT = 3;
    private static final double TEND_FIELD_RADIUS = 8.0;
    private static final int TEND_FIELD_INTERVAL_TICKS = 20;

    private static final BlockBreaker breaker = new BlockBreaker();

    private static volatile boolean tendActive;
    private static boolean tendTickerRegistered;
    private static BlockPos tendCenter;
    private static CropKind tendKind;
    private static Block tendBlock;
    private static int tendCooldown;

    private HarvestController() {}

    /** Non-null only if state at pos is a recognized, FULLY GROWN harvestable plant. */
    public static CropKind classify(Level level, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CropBlock crop) {
            return crop.isMaxAge(state) ? CropKind.WHEAT_FAMILY : null;
        }
        if (block instanceof NetherWartBlock) {
            return state.getValue(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE ? CropKind.NETHER_WART : null;
        }
        if (block == Blocks.MELON || block == Blocks.PUMPKIN) {
            return CropKind.MELON_PUMPKIN;
        }
        if (block instanceof SugarCaneBlock) {
            return sugarCaneHeight(level, pos) >= MIN_SUGAR_CANE_HEIGHT ? CropKind.SUGAR_CANE : null;
        }
        return null;
    }

    public static void harvestOne(BlockPos pos, CropKind kind) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        switch (kind) {
            case WHEAT_FAMILY -> {
                Block block = level.getBlockState(pos).getBlock();
                Item seed = seedFor(block);
                breaker.breakBlock(pos, () -> replant(pos, seed));
            }
            case NETHER_WART -> breaker.breakBlock(pos, () -> replant(pos, Items.NETHER_WART));
            case MELON_PUMPKIN -> breaker.breakBlock(pos, () -> {});
            case SUGAR_CANE -> {
                BlockPos top = sugarCaneTop(level, pos);
                breaker.breakBlock(top, () -> {});
            }
        }
    }

    /** "Extends to harvest all adjacent crops nearby" -- scans a radius around pos for every matching, fully-grown crop of the SAME kind and harvests them one at a time, stopping once percent-of-however-many-were-found is reached. Same fixed-snapshot-count shape as KillPercentController. */
    public static void harvestPercent(BlockPos pos, CropKind kind, int percent) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        java.util.List<BlockPos> matches = findMatching(level, pos, kind, TEND_FIELD_RADIUS);
        int target = (int) Math.ceil(matches.size() * percent / 100.0);
        if (target <= 0) {
            StatusIndicator.show("No matching fully-grown crops nearby.");
            return;
        }
        StatusIndicator.show("Harvesting " + target + " of " + matches.size() + " (" + percent + "%)");
        harvestSequence(matches.subList(0, Math.min(target, matches.size())), 0, kind);
    }

    private static void harvestSequence(java.util.List<BlockPos> positions, int index, CropKind kind) {
        if (index >= positions.size()) return;
        BlockPos pos = positions.get(index);
        Level level = Minecraft.getInstance().level;
        if (level == null || classify(level, pos, level.getBlockState(pos)) != kind) {
            harvestSequence(positions, index + 1, kind); // already gone/changed since the scan -- skip
            return;
        }
        switch (kind) {
            case WHEAT_FAMILY -> {
                Item seed = seedFor(level.getBlockState(pos).getBlock());
                breaker.breakBlock(pos, () -> { replant(pos, seed); harvestSequence(positions, index + 1, kind); });
            }
            case NETHER_WART -> breaker.breakBlock(pos, () -> { replant(pos, Items.NETHER_WART); harvestSequence(positions, index + 1, kind); });
            case MELON_PUMPKIN -> breaker.breakBlock(pos, () -> harvestSequence(positions, index + 1, kind));
            case SUGAR_CANE -> {
                BlockPos top = sugarCaneTop(level, pos);
                breaker.breakBlock(top, () -> harvestSequence(positions, index + 1, kind));
            }
        }
    }

    /** Continuous: re-scans every TEND_FIELD_INTERVAL_TICKS and harvests whatever's newly fully-grown. Runs until stopTendField() -- there's no natural "done" state, crops keep growing back. */
    public static void startTendField(BlockPos center, CropKind kind) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        tendCenter = center;
        tendKind = kind;
        tendBlock = level.getBlockState(center).getBlock();
        tendActive = true;
        tendCooldown = 0;
        ensureTendTicker();
        StatusIndicator.show("Tending field around @" + center.getX() + "," + center.getY() + "," + center.getZ());
    }

    public static void stopTendField() {
        tendActive = false;
    }

    public static boolean isTending() {
        return tendActive;
    }

    private static void ensureTendTicker() {
        if (tendTickerRegistered) return;
        tendTickerRegistered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tendTick(client);
            } catch (RuntimeException e) {
                System.err.println("[ardor] tend field failed: " + e);
                tendActive = false;
            }
        });
    }

    private static void tendTick(Minecraft client) {
        if (!tendActive) return;
        if (breaker.isActive()) return; // mid-harvest, let it finish
        if (tendCooldown-- > 0) return;
        tendCooldown = TEND_FIELD_INTERVAL_TICKS;

        Level level = client.level;
        if (level == null) {
            tendActive = false;
            return;
        }
        var matches = findMatching(level, tendCenter, tendKind, TEND_FIELD_RADIUS);
        if (!matches.isEmpty()) {
            harvestOne(matches.get(0), tendKind);
        }
    }

    private static java.util.List<BlockPos> findMatching(Level level, BlockPos center, CropKind kind, double radius) {
        java.util.List<BlockPos> found = new java.util.ArrayList<>();
        AABB box = new AABB(center).inflate(radius);
        BlockPos min = BlockPos.containing(box.minX, box.minY, box.minZ);
        BlockPos max = BlockPos.containing(box.maxX, box.maxY, box.maxZ);
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (classify(level, pos, state) == kind) found.add(pos);
                }
            }
        }
        return found;
    }

    private static void replant(BlockPos pos, Item seed) {
        JsonObject action = new JsonObject();
        action.addProperty("action", "place");
        JsonObject block = new JsonObject();
        block.addProperty("id", BuiltInRegistries.ITEM.getKey(seed).toString());
        action.add("block", block);
        JsonObject position = new JsonObject();
        position.addProperty("x", pos.getX());
        position.addProperty("y", pos.getY());
        position.addProperty("z", pos.getZ());
        action.add("position", position);
        action.addProperty("facing", "up"); // crops always plant onto the block below
        GameActionController.dispatch(action);
    }

    private static Item seedFor(Block cropBlock) {
        if (cropBlock == Blocks.WHEAT) return Items.WHEAT_SEEDS;
        if (cropBlock == Blocks.CARROTS) return Items.CARROT;
        if (cropBlock == Blocks.POTATOES) return Items.POTATO;
        if (cropBlock == Blocks.BEETROOTS) return Items.BEETROOT_SEEDS;
        throw new IllegalArgumentException("Not a replantable crop: " + cropBlock);
    }

    private static int sugarCaneHeight(Level level, BlockPos anyPartOfStack) {
        BlockPos base = sugarCaneBase(level, anyPartOfStack);
        int height = 0;
        while (level.getBlockState(base.above(height)).getBlock() instanceof SugarCaneBlock) height++;
        return height;
    }

    private static BlockPos sugarCaneBase(Level level, BlockPos anyPartOfStack) {
        BlockPos pos = anyPartOfStack;
        while (level.getBlockState(pos.below()).getBlock() instanceof SugarCaneBlock) pos = pos.below();
        return pos;
    }

    private static BlockPos sugarCaneTop(Level level, BlockPos anyPartOfStack) {
        BlockPos pos = anyPartOfStack;
        while (level.getBlockState(pos.above()).getBlock() instanceof SugarCaneBlock) pos = pos.above();
        return pos;
    }
}
