package com.ardor.game;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * A live spatial index of block positions matching a "watched" block type,
 * maintained via ClientChunkEvents.CHUNK_LOAD/CHUNK_UNLOAD instead of
 * rescanning a radius cube on every search -- "utilize the events already in
 * the fabric framework that register blocks when they load and unregister
 * them when they unload... great for searching for blocks." Directly
 * addresses the perf concern flagged earlier in TODO.md about mine's
 * brute-force cube scan at large radii.
 *
 * ClientChunkEvents.Load/Unload.onChunkLoad/onChunkUnload(ClientLevel,
 * LevelChunk) and LevelChunk.getBlockState/ChunkAccess.getPos confirmed via
 * javap against the actual bundled fabric-lifecycle-events-v1 and
 * minecraft-common jars this session.
 *
 * A block type is only indexed once something actually asks for it
 * (watch()) -- there's no way to know in advance which of the ~1000+ block
 * types in the game are ever going to matter, and indexing all of them
 * would just be the world's own block data duplicated in memory. The first
 * ask for a not-yet-watched type pays a one-time radius scan to seed
 * already-loaded chunks (the exact cost the old brute-force approach paid
 * on every call); every later chunk load/unload and every later query for
 * that same type is cheap from then on.
 *
 * Self-healing, not push-updated: nothing here listens for individual block
 * changes (mining, placing) -- nearest() re-checks each candidate's live
 * state and evicts it from the index if it no longer matches, rather than
 * trusting stale data. Cheap since it's bounded by index size (typically
 * small -- ores, specific block types), not world size.
 */
public final class BlockIndex {

    private BlockIndex() {}

    private static final Set<Block> watched = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<Block, Set<BlockPos>> index = new ConcurrentHashMap<>();
    private static boolean registered;

    public static void register() {
        if (registered) return;
        registered = true;
        ClientChunkEvents.CHUNK_LOAD.register(BlockIndex::onLoad);
        ClientChunkEvents.CHUNK_UNLOAD.register(BlockIndex::onUnload);
    }

    private static void onLoad(ClientLevel level, LevelChunk chunk) {
        try {
            onLoadInner(level, chunk);
        } catch (RuntimeException e) {
            System.err.println("[ardor] BlockIndex chunk load failed: " + e);
        }
    }

    private static void onLoadInner(ClientLevel level, LevelChunk chunk) {
        if (watched.isEmpty()) return;
        var pos = chunk.getPos();
        int minX = pos.getMinBlockX(), maxX = pos.getMaxBlockX();
        int minZ = pos.getMinBlockZ(), maxZ = pos.getMaxBlockZ();
        int minY = level.getMinY(), maxY = level.getMaxY();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y < maxY; y++) {
                    cursor.set(x, y, z);
                    Block block = chunk.getBlockState(cursor).getBlock();
                    if (watched.contains(block)) {
                        index.computeIfAbsent(block, k -> ConcurrentHashMap.newKeySet()).add(cursor.immutable());
                    }
                }
            }
        }
    }

    private static void onUnload(ClientLevel level, LevelChunk chunk) {
        try {
            var pos = chunk.getPos();
            int cx = pos.x(), cz = pos.z();
            for (Set<BlockPos> positions : index.values()) {
                positions.removeIf(p -> (p.getX() >> 4) == cx && (p.getZ() >> 4) == cz);
            }
        } catch (RuntimeException e) {
            System.err.println("[ardor] BlockIndex chunk unload failed: " + e);
        }
    }

    /** No-op if block is already watched. Otherwise seeds it once via a radius scan (the old brute-force cost, paid exactly once per type) so future chunk loads and queries have something to build on. */
    public static void watch(Block block, BlockPos center, int radius, Level level) {
        if (!watched.add(block)) return;
        BlockPos min = center.offset(-radius, -radius, -radius);
        BlockPos max = center.offset(radius, radius, radius);
        Set<BlockPos> found = BlockPos.betweenClosedStream(min, max)
                .filter(p -> level.getBlockState(p).getBlock() == block)
                .map(BlockPos::immutable)
                .collect(Collectors.toSet());
        index.computeIfAbsent(block, k -> ConcurrentHashMap.newKeySet()).addAll(found);
    }

    /** Ensures every target block is watched (seeding if new), self-heals stale entries against live world state, and returns up to `limit` matches within `radius` of `center`, nearest first. */
    public static List<BlockPos> nearest(Set<Block> targets, BlockPos center, int radius, Level level, int limit) {
        for (Block b : targets) watch(b, center, radius, level);

        List<BlockPos> candidates = new ArrayList<>();
        double radiusSq = (double) radius * radius;
        for (Block b : targets) {
            Set<BlockPos> positions = index.get(b);
            if (positions == null) continue;
            positions.removeIf(p -> level.getBlockState(p).getBlock() != b);
            for (BlockPos p : positions) {
                if (p.distSqr(center) <= radiusSq) candidates.add(p);
            }
        }
        candidates.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
        return candidates.size() > limit ? candidates.subList(0, limit) : candidates;
    }
}
