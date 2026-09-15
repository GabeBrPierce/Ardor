package com.ardor.game;

import com.ardor.config.ArdorConfig;
import com.ardor.pathing.AStarPathfinder;
import com.ardor.region.RegionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Supplies walkability/cost information to AStarPathfinder from a live Level.
 * Movement: 8 directions (4 cardinal + 4 diagonal, corner-clip checked), a
 * single-block step up (requires 3 full cells of clearance at the STARTING
 * position -- feet, head, and one more above the head to jump through, not
 * just the usual 2-cell standability at the destination; see neighbors()),
 * falls up to MAX_FALL blocks (scanned down from each horizontal step to the
 * first standable landing), and now digging through
 * config.breakableBlocks when that's the only/best way through. Water is
 * passable (swimming, at a cost penalty); other fluids (lava) are not.
 * Fire/soul fire/cactus/magma block/powder snow are treated as blocked, not
 * just "open space."
 *
 * Digging: the two body-height cells (standing pos + pos.above()) may be
 * solid-but-breakable and still count as a valid neighbor, at a real cost
 * penalty -- PathExecutor breaks the block(s) via blocksToDig() when it
 * actually reaches that step. The FLOOR (pos.below()) is deliberately NOT
 * diggable-eligible: clearing your own floor before standing on it would
 * just mean falling through, so a landing spot must already be solid ground
 * or water, never something still needing to be broken.
 *
 * Still not modeled: fall damage (falls just get a mild distance-scaled cost
 * penalty, not a real damage/safety check), ladders/vines, sprint-jumps.
 *
 * AStarPathfinder's heuristic assumes roughly 1 cost unit per block of
 * remaining distance -- multi-block falls can now cover several Y in one
 * step for well under that per-block cost, so the heuristic can overestimate
 * in fall-heavy terrain. Not fixed: a heuristic admissible under free-form
 * falls would need to assume every remaining step could fall the max amount,
 * which would weaken it too much for the common (non-falling) case. A* still
 * always returns *a* valid path; it just isn't guaranteed shortest once
 * falls (or now, digging) are involved.
 *
 * FluidTags.WATER, Holder.is(TagKey) (via FluidState.typeHolder()), and
 * BlockStateBase.isAir()/getCollisionShape() confirmed via jar inspection
 * this session. Blocks.FIRE/SOUL_FIRE/CACTUS/MAGMA_BLOCK/POWDER_SNOW assumed
 * present (extremely stable vanilla constants) but not individually grepped.
 */
public final class BlockWorldMovement implements AStarPathfinder.MovementModel {

    private static final int[][] ALL_8 = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
    private static final int MAX_FALL = 3;
    // Digging is meant as a last resort for genuinely blocked paths, not a shortcut past minor
    // terrain -- breaking a block takes real seconds, but the pathfinder only sees an edge weight,
    // so this has to be high enough that a multi-block walk-around always wins over tunneling
    // through a dirt bump. First shipped at 3.0 (roughly "2-3 extra footsteps"), which was too
    // cheap: confirmed live (2026-08-31) that a "mine oak log" errand dug through nearby terrain
    // instead of stepping around it, looking indistinguishable from the bot just standing still
    // (movement freezes and the camera locks onto the dig target while breaking).
    private static final double DIG_COST = 25.0; // per diggable body cell -- only worth it over a long detour

    private final Level level;
    private final Set<Block> breakableBlocks;

    public BlockWorldMovement(Level level) {
        this.level = level;
        this.breakableBlocks = resolveBreakableBlocks();
    }

    private static Set<Block> resolveBreakableBlocks() {
        Set<Block> result = new HashSet<>();
        for (String id : ArdorConfig.get().breakableBlocks) {
            BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id)).ifPresent(result::add);
        }
        return result;
    }

    @Override
    public List<BlockPos> neighbors(BlockPos from) {
        List<BlockPos> result = new ArrayList<>();
        // Stepping up 1 block needs headroom to jump, not just room to stand once you land: the
        // player's hitbox rises through the space above its own head mid-jump, so a 3rd cell --
        // one above "from"'s own head, not the destination's -- has to be open too. Previously
        // only the destination's own 2-cell standability was checked, which would offer a step-up
        // even when the CURRENT position had a block directly overhead (physically impossible to
        // jump from; a real player would just bonk their head and go nowhere). Computed once per
        // `from` since it doesn't depend on direction.
        boolean canJumpUp = isPassableOrDiggable(from.above(2));
        for (int[] d : ALL_8) {
            boolean diagonal = d[0] != 0 && d[1] != 0;
            BlockPos sameLevel = from.offset(d[0], 0, d[1]);
            tryAdd(result, from, sameLevel, diagonal);
            if (canJumpUp) {
                tryAdd(result, from, from.offset(d[0], 1, d[1]), diagonal);
            }

            BlockPos landing = findFallLanding(sameLevel);
            if (landing != null) tryAdd(result, from, landing, diagonal);
        }
        return result;
    }

    /** Scans down from columnTop (exclusive) for the first standable landing within MAX_FALL blocks, or null. */
    private BlockPos findFallLanding(BlockPos columnTop) {
        for (int k = 1; k <= MAX_FALL; k++) {
            BlockPos pos = columnTop.below(k);
            if (!isPassable(pos)) return null; // solid in the fall path -- blocked, not a landing (falls don't dig)
            if (isStandable(pos)) return pos;
        }
        return null;
    }

    private void tryAdd(List<BlockPos> out, BlockPos from, BlockPos candidate, boolean diagonal) {
        if (!isStandable(candidate)) return;
        if (diagonal) {
            BlockPos corner1 = new BlockPos(candidate.getX(), from.getY(), from.getZ());
            BlockPos corner2 = new BlockPos(from.getX(), from.getY(), candidate.getZ());
            if (!isOpenOrDiggable(corner1) || !isOpenOrDiggable(corner2)) return;
        }
        out.add(candidate);
    }

    /** Room for the player's two-block hitbox (open OR diggable), and either solid ground or water underneath (never diggable). */
    public boolean isStandable(BlockPos pos) {
        if (!isOpenOrDiggable(pos)) return false;
        BlockPos below = pos.below();
        BlockState belowState = level.getBlockState(below);
        boolean solidFloor = !belowState.getCollisionShape(level, below).isEmpty();
        boolean waterFloor = level.getFluidState(below).typeHolder().is(FluidTags.WATER);
        return solidFloor || waterFloor;
    }

    /** Which of pos/pos.above() are solid-but-breakable and need clearing before standing at pos. Empty if pos is already fully open. */
    public List<BlockPos> blocksToDig(BlockPos pos) {
        List<BlockPos> toDig = new ArrayList<>();
        if (!isPassable(pos) && isBreakable(pos)) toDig.add(pos);
        BlockPos above = pos.above();
        if (!isPassable(above) && isBreakable(above)) toDig.add(above);
        return toDig;
    }

    /** Two vertically-stacked cells that are each either already passable or diggable. */
    private boolean isOpenOrDiggable(BlockPos pos) {
        return isPassableOrDiggable(pos) && isPassableOrDiggable(pos.above());
    }

    private boolean isPassableOrDiggable(BlockPos pos) {
        return isPassable(pos) || isBreakable(pos);
    }

    /** "DisableImplicitDestruction -- disable the mod deciding 'we should break these blocks to get to the target'." Checked per-position (not once for the whole path) since a path can cross a region boundary mid-route. Only gates this INFERRED dig-through-obstacles decision, not an explicit mine/Break-Blocks-Within command. */
    private boolean isBreakable(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!isHazard(state.getBlock()) && breakableBlocks.contains(state.getBlock())) {
            return !RegionManager.get().hasFlag(RegionManager.currentProfileKey(), pos, r -> r.disableImplicitDestruction);
        }
        return false;
    }

    private boolean isPassable(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (isHazard(state.getBlock())) return false;
        if (!state.getCollisionShape(level, pos).isEmpty()) return false;
        var fluid = level.getFluidState(pos);
        return fluid.isEmpty() || fluid.typeHolder().is(FluidTags.WATER);
    }

    private boolean isHazard(Block block) {
        return block == Blocks.FIRE || block == Blocks.SOUL_FIRE || block == Blocks.CACTUS
                || block == Blocks.MAGMA_BLOCK || block == Blocks.POWDER_SNOW;
    }

    @Override
    public double cost(BlockPos from, BlockPos to) {
        double base = (from.getX() != to.getX() && from.getZ() != to.getZ()) ? Math.sqrt(2) : 1.0;
        int dy = to.getY() - from.getY();
        if (dy > 0) base += 0.5; // jump
        else if (dy < 0) base += 0.2 * -dy; // fall -- mild distance-scaled penalty, no fall-damage modeling
        if (level.getFluidState(to).typeHolder().is(FluidTags.WATER)) base += 1.0; // prefer dry paths
        base += DIG_COST * blocksToDig(to).size();
        return base;
    }
}
