package com.ardor.container;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LavaCauldronBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * "A lava cauldron + an empty bucket == a lava bucket" -- reads/fills cauldrons directly off the
 * block state rather than going through the real vanilla interaction dispatch
 * (net.minecraft.core.cauldron.CauldronInteractions), which is server-authoritative and not
 * clearly client-safe to invoke directly. Same "direct field write" simplification this codebase
 * already accepts elsewhere (container.SubContainerAccess for shulker/bundle contents,
 * game.ContainerSearch.takeMatching for physical containers) -- fillBucket below just swaps the
 * ItemStack and resets the block state itself.
 *
 * Verified live via `javap`/`javap -c` against the real 26.1.2 client jar, not assumed: there are
 * FOUR distinct cauldron block classes, not one shared "leveled" block for every liquid --
 * CauldronBlock (empty CAULDRON), LayeredCauldronBlock (WATER_CAULDRON and POWDER_SNOW_CAULDRON,
 * both real constructions in Blocks.<clinit>, with a real IntegerProperty LEVEL 1-3), and a
 * SEPARATE LavaCauldronBlock (LAVA_CAULDRON) whose isFull(BlockState) is hardcoded true and which
 * has NO LEVEL property at all -- a lava cauldron is always either full or gone, never partial.
 * Real bucket-fill-from-cauldron only ever works from a full cauldron in vanilla (a partially
 * filled water/powder-snow cauldron can only fill a bottle, not a bucket), so liquidAt requires
 * LEVEL == MAX_FILL_LEVEL for the two LayeredCauldronBlock cases too, not just "any level > 0".
 */
public final class CauldronAccess {

    private CauldronAccess() {}

    public enum Liquid { LAVA, WATER, POWDER_SNOW }

    /** Null if the block at pos isn't a cauldron, or isn't currently full enough to fill a bucket from. */
    public static Liquid liquidAt(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        if (block == Blocks.LAVA_CAULDRON) {
            return ((LavaCauldronBlock) block).isFull(state) ? Liquid.LAVA : null;
        }
        if (block == Blocks.WATER_CAULDRON && isFullLayered(state)) return Liquid.WATER;
        if (block == Blocks.POWDER_SNOW_CAULDRON && isFullLayered(state)) return Liquid.POWDER_SNOW;
        return null;
    }

    private static boolean isFullLayered(BlockState state) {
        return state.getValue(LayeredCauldronBlock.LEVEL) >= LayeredCauldronBlock.MAX_FILL_LEVEL;
    }

    public static String bucketItemId(Liquid liquid) {
        return switch (liquid) {
            case LAVA -> "minecraft:lava_bucket";
            case WATER -> "minecraft:water_bucket";
            case POWDER_SNOW -> "minecraft:powder_snow_bucket";
        };
    }

    /** Removes one empty bucket from inv and returns the filled bucket, resetting the cauldron at pos to empty (Blocks.CAULDRON) -- null (no mutation) if there's no empty bucket to use, or the cauldron isn't actually full any more (changed since the last scan). */
    public static ItemStack fillBucket(Level level, BlockPos pos, Inventory inv) {
        Liquid liquid = liquidAt(level, pos);
        if (liquid == null) return null;

        int bucketSlot = -1;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).getItem() == Items.BUCKET) {
                bucketSlot = i;
                break;
            }
        }
        if (bucketSlot < 0) return null;

        inv.removeItem(bucketSlot, 1);
        level.setBlockAndUpdate(pos, Blocks.CAULDRON.defaultBlockState());

        Item filled = switch (liquid) {
            case LAVA -> Items.LAVA_BUCKET;
            case WATER -> Items.WATER_BUCKET;
            case POWDER_SNOW -> Items.POWDER_SNOW_BUCKET;
        };
        return new ItemStack(filled);
    }
}
