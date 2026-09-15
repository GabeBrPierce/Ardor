package com.ardor.struct;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Synthesizes a plausible build order for a snapshot that has no real recorded placement trace
 * behind it (an imported .litematic, see LitematicaImporter) -- there is no way to recover HOW a
 * human actually built it, since Litematica never captured that, so this is a heuristic
 * reconstruction, not a recorded human session. Bottom-up, one attachment face per block, picked
 * from whatever's already been placed earlier in the same plan (or assumed-solid ground for the
 * very first layer) -- reads more mechanical than PlacementRecorder's real data by construction,
 * since it's an ordering algorithm, not a memory of an actual build.
 *
 * Known rough edges (see TODO.md): a block with no already-placed neighbor in any of the 6
 * directions and not in the bottom layer falls back to DOWN and just assumes something solid is
 * there in the real world -- true for most builds resting on existing terrain, wrong for a
 * genuinely floating design. Standing-spot search only tries the 4 horizontal neighbors plus
 * straight above; a fully enclosed interior block can fail to find a real one and gets a
 * best-effort fallback instead.
 */
public final class BuildPlanner {

    private static final Direction[] FACE_PREFERENCE =
            {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};

    private BuildPlanner() {}

    public static List<StructFile.PlacementEntry> plan(List<StructFile.BlockEntry> blocks) {
        List<StructFile.BlockEntry> ordered = new ArrayList<>(blocks);
        ordered.sort(Comparator.<StructFile.BlockEntry>comparingInt(b -> b.y)
                .thenComparingInt(b -> b.z)
                .thenComparingInt(b -> b.x));

        Set<BlockPos> allBlocks = new HashSet<>();
        for (StructFile.BlockEntry b : ordered) allBlocks.add(new BlockPos(b.x, b.y, b.z));

        int bottomY = ordered.isEmpty() ? 0 : ordered.get(0).y;
        Set<BlockPos> placed = new HashSet<>();
        List<StructFile.PlacementEntry> plan = new ArrayList<>(ordered.size());

        for (StructFile.BlockEntry b : ordered) {
            BlockPos target = new BlockPos(b.x, b.y, b.z);
            Direction face = pickFace(target, placed, b.y == bottomY);
            BlockPos standPos = pickStandingSpot(target, allBlocks);

            StructFile.PlacementEntry p = new StructFile.PlacementEntry();
            p.x = b.x;
            p.y = b.y;
            p.z = b.z;
            p.block = b.block;
            p.properties = b.properties;
            p.standX = standPos.getX() + 0.5;
            p.standY = standPos.getY();
            p.standZ = standPos.getZ() + 0.5;
            p.face = face.name();
            p.shift = standPos.getY() == target.getY();
            p.tickDelta = 10; // no real timing data to draw on -- fixed pacing, see class doc

            double dx = target.getX() + 0.5 - p.standX;
            double dy = target.getY() + 0.5 - (p.standY + 1.62); // approx eye height
            double dz = target.getZ() + 0.5 - p.standZ;
            double distXZ = Math.sqrt(dx * dx + dz * dz);
            p.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            p.pitch = (float) net.minecraft.util.Mth.clamp(-Math.toDegrees(Math.atan2(dy, distXZ)), -90.0, 90.0);

            plan.add(p);
            placed.add(target);
        }
        return plan;
    }

    private static Direction pickFace(BlockPos target, Set<BlockPos> placed, boolean isBottomLayer) {
        for (Direction face : FACE_PREFERENCE) {
            BlockPos neighbor = target.relative(face.getOpposite());
            if (placed.contains(neighbor)) return face;
        }
        return Direction.DOWN; // assume ground/existing terrain -- see class doc
    }

    private static BlockPos pickStandingSpot(BlockPos target, Set<BlockPos> allBlocks) {
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos candidate = target.relative(d);
            if (!allBlocks.contains(candidate)) return candidate;
        }
        BlockPos above = target.above();
        if (!allBlocks.contains(above)) return above;
        return target.north(); // best-effort fallback for a fully enclosed block -- see class doc
    }
}
