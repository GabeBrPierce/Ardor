package com.ardor.game;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Could we literally send a query as text and return matches?" -- a single
 * substring search across blocks and entities near a point, shared by both
 * front doors that need it: QueryController's `query search <text>` (the
 * ascii-grammar verb the trained LLM actually speaks -- kept in the exact
 * same shape as `query time` deliberately, so nothing new has to be relearned)
 * and the bridge's `world.search` (for the companion app calling directly).
 * One implementation, two callers, so the two protocols don't drift apart on
 * this capability the way the rest of the bridge already has from the ascii
 * grammar.
 *
 * Blocks: matched via a one-time scan of BuiltInRegistries.BLOCK for ids
 * whose path contains the search text, then BlockIndex.nearest() for actual
 * positions (same chunk-load/unload-maintained index world.nearestBlocks
 * already uses -- no separate scan-the-world-every-call cost).
 * Entities: matched via a live radius scan (no index -- entities move, an
 * index would be stale the instant it was built) filtered by type id.
 */
public final class GameObjectSearch {

    private GameObjectSearch() {}

    public record Match(String kind, String id, BlockPos pos, Integer entityId, double distance) {}

    public static List<Match> search(String text, BlockPos center, Level level, int radius, int limit) {
        String needle = text.toLowerCase();
        List<Match> matches = new ArrayList<>();
        matches.addAll(matchBlocks(needle, center, level, radius, limit));
        matches.addAll(matchEntities(needle, center, level, radius));
        matches.sort(Comparator.comparingDouble(Match::distance));
        return matches.size() > limit ? matches.subList(0, limit) : matches;
    }

    private static List<Match> matchBlocks(String needle, BlockPos center, Level level, int radius, int limit) {
        Set<Block> matchingTypes = new HashSet<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            if (id != null && id.getPath().contains(needle)) matchingTypes.add(block);
        }
        if (matchingTypes.isEmpty()) return List.of();

        List<Match> result = new ArrayList<>();
        for (BlockPos pos : BlockIndex.nearest(matchingTypes, center, radius, level, limit)) {
            Block block = level.getBlockState(pos).getBlock();
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            result.add(new Match("block", id != null ? id.toString() : "unknown", pos, null, Math.sqrt(pos.distSqr(center))));
        }
        return result;
    }

    private static List<Match> matchEntities(String needle, BlockPos center, Level level, int radius) {
        List<Match> result = new ArrayList<>();
        Vec3 centerVec = Vec3.atCenterOf(center);
        AABB box = new AABB(center).inflate(radius);
        for (Entity e : level.getEntitiesOfClass(Entity.class, box)) {
            Identifier typeId = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            if (typeId == null || !typeId.getPath().contains(needle)) continue;
            result.add(new Match("entity", typeId.toString(), e.blockPosition(), e.getId(),
                    Math.sqrt(e.position().distanceToSqr(centerVec))));
        }
        return result;
    }
}
