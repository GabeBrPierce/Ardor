package com.ardor.struct;

import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BlockState <-> {id, properties} conversion shared by every part of the .struct pipeline
 * (LitematicaImporter reading a palette, PlacementRecorder logging what actually got placed,
 * StructBuilder resolving what to place) -- built on the game's own NbtUtils.readBlockState/
 * writeBlockState (the same {Name, Properties} shape vanilla structure blocks and Litematica's
 * palette both use) instead of hand-rolling property (de)serialization three times.
 */
final class BlockStateCodec {

    private BlockStateCodec() {}

    static String id(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    static Map<String, String> properties(BlockState state) {
        Map<String, String> out = new LinkedHashMap<>();
        CompoundTag propsTag = NbtUtils.writeBlockState(state).getCompoundOrEmpty("Properties");
        for (String key : propsTag.keySet()) {
            propsTag.getString(key).ifPresent(v -> out.put(key, v));
        }
        return out;
    }

    static BlockState decode(String blockId, Map<String, String> properties) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        CompoundTag props = new CompoundTag();
        properties.forEach(props::putString);
        tag.put("Properties", props);
        HolderGetter<Block> blocks = Minecraft.getInstance().level.registryAccess().lookupOrThrow(Registries.BLOCK);
        return NbtUtils.readBlockState(blocks, tag);
    }
}
