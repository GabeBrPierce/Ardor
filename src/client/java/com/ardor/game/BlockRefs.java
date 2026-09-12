package com.ardor.game;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

import java.util.HashSet;
import java.util.Set;

/**
 * Resolves a block reference token to one or more Blocks -- either a plain
 * id ("oak_log", "minecraft:oak_log") or a vanilla block tag prefixed with
 * '#' ("#logs", "#minecraft:logs"). Tag support exists so a caller can ask
 * for "any wood" without the mod inventing its own category vocabulary --
 * '#logs'/'#planks'/etc. are exactly the tag names vanilla commands already
 * use, which an LLM is far more likely to already know than a bespoke one.
 *
 * TagKey.create/Registry.getTagOrEmpty confirmed via javap against the
 * actual bundled minecraft-common.jar this session (see BlockIndex's
 * javadoc for the same verification).
 */
public final class BlockRefs {

    private BlockRefs() {}

    public static Set<Block> resolve(String token) {
        if (token.startsWith("#")) return resolveTag(expandId(token.substring(1)));
        return Set.of(resolveBlock(expandId(token)));
    }

    private static Set<Block> resolveTag(String tagId) {
        TagKey<Block> key = TagKey.create(Registries.BLOCK, Identifier.parse(tagId));
        Set<Block> result = new HashSet<>();
        for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(key)) {
            result.add(holder.value());
        }
        if (result.isEmpty()) throw new IllegalArgumentException("unknown or empty tag: #" + tagId);
        return result;
    }

    private static Block resolveBlock(String id) {
        return BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id))
                .orElseThrow(() -> new IllegalArgumentException("unknown block id: " + id));
    }

    private static String expandId(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }
}
