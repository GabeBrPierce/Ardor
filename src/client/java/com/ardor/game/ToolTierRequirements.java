package com.ardor.game;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/**
 * Tool-tier gating for mining: does this block need a specific pickaxe
 * tier, and does the bot already have (or hold) one that's actually
 * correct for it.
 *
 * Verified live via `javap`/`javap -c` against the real 26.1.2 client jar
 * (D:\dev\gradle\caches\fabric-loom\26.1.2\minecraft-client.jar), not
 * assumed from pre-26.1 Minecraft memory -- same discipline as every other
 * API check this project has had to do for this build:
 *
 * - The old numeric-harvest-level `Tier` enum is gone. Its replacement,
 *   `net.minecraft.world.item.ToolMaterial`, is a real record with
 *   WOOD/STONE/COPPER/IRON/DIAMOND/GOLD/NETHERITE constants -- confirmed by
 *   `javap -p`. The user's own stated hierarchy ("wood -> stone -> copper
 *   -> iron -> diamond -> netherite") is exactly right for this build,
 *   copper included; GOLD also exists but, matching the vanilla convention
 *   this replaced (gold was never part of the harvest-level ladder either),
 *   it isn't a rung on the mining-progression ladder -- fast and fragile,
 *   not gating anything -- so it's deliberately left out of Tier below.
 * - There's no numeric level any more. Each ToolMaterial instead carries
 *   `incorrectBlocksForDrops()`, a `TagKey<Block>` of exactly the blocks
 *   that material can NOT correctly harvest. Confirmed by `javap -c`
 *   disassembly of `ToolMaterial.applyToolProperties()`: every real
 *   pickaxe/axe/shovel/hoe is built with a `Tool` component of exactly two
 *   rules, in this order -- `Tool.Rule.deniesDrops(incorrectBlocksForDrops)`
 *   then `Tool.Rule.minesAndDrops(mineableWithXTag, speed)` -- so a
 *   material's own incorrectBlocksForDrops tag genuinely IS the real
 *   correctness signal every tool item is built from, not a
 *   reimplementation of it.
 * - `BlockState.requiresCorrectToolForDrops()` DOES exist, despite
 *   ToolSelector's class javadoc claiming otherwise -- it's declared on
 *   `BlockBehaviour.BlockStateBase`, which `BlockState` extends, so a
 *   `javap` limited to BlockState's own declared members (what that
 *   comment was based on) misses it. Confirmed directly against
 *   `BlockBehaviour$BlockStateBase`. See ToolSelector's corrected javadoc.
 * - `BlockState` has no direct `is(TagKey<Block>)` any more either. The
 *   real path -- same pattern `BridgeQueries.blockInfo` already uses for
 *   `FluidTags.WATER` via `fluid.typeHolder().is(...)` -- is
 *   `state.typeHolder().is(tagKey)`: `typeHolder()` returns a
 *   `Holder<Block>`, and `Holder` itself declares `is(TagKey<T>)`.
 */
public final class ToolTierRequirements {

    private ToolTierRequirements() {}

    /** Declared low -> high; Tier.values() below relies on this order. */
    public enum Tier {
        WOOD(ToolMaterial.WOOD, "minecraft:wooden_pickaxe"),
        STONE(ToolMaterial.STONE, "minecraft:stone_pickaxe"),
        COPPER(ToolMaterial.COPPER, "minecraft:copper_pickaxe"),
        IRON(ToolMaterial.IRON, "minecraft:iron_pickaxe"),
        DIAMOND(ToolMaterial.DIAMOND, "minecraft:diamond_pickaxe"),
        NETHERITE(ToolMaterial.NETHERITE, "minecraft:netherite_pickaxe");

        final ToolMaterial material;
        final String pickaxeId;

        Tier(ToolMaterial material, String pickaxeId) {
            this.material = material;
            this.pickaxeId = pickaxeId;
        }
    }

    private static final Tier[] ORDER = Tier.values();

    /** Null if this block doesn't gate on tool tier at all. */
    public static Tier requiredTier(BlockState state) {
        if (!state.requiresCorrectToolForDrops()) return null;
        for (Tier t : ORDER) {
            if (!state.typeHolder().is(t.material.incorrectBlocksForDrops())) return t;
        }
        // Shouldn't happen for any real pickaxe-gated block (NETHERITE's own incorrectBlocksForDrops
        // tag should be empty/near-empty) -- conservative fallback rather than silently returning null
        // and letting a doomed mine attempt through.
        return Tier.NETHERITE;
    }

    /** True if some item already in inv has a real Tool component correct for this exact block -- the same ground-truth check a real pickaxe swing uses, not a synthetic tier compare (so an owned GOLD tool, e.g., is still recognized correctly even though GOLD isn't in the Tier ladder above). */
    public static boolean hasAdequateTool(Inventory inv, BlockState state) {
        Predicate<ItemStack> match = adequateToolPredicate(state);
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            if (match.test(inv.getItem(slot))) return true;
        }
        return false;
    }

    /** Same check, phrased as a Predicate<ItemStack> -- used for both inventory and (via ContainerSearch) nearby-container scans. */
    public static Predicate<ItemStack> adequateToolPredicate(BlockState state) {
        return stack -> {
            if (stack.isEmpty()) return false;
            Tool tool = stack.get(DataComponents.TOOL);
            return tool != null && tool.isCorrectForDrops(state);
        };
    }
}
