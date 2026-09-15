package com.ardor.struct;

import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads an existing .litematic (gzipped NBT) into a flat block list, no placement order --
 * Litematica only ever records the end state, so there is no "how it was built" to extract; see
 * StructFile's own doc and BuildPlanner for what fills that gap on import. Uses the game's own
 * NbtUtils.readBlockState/writeBlockState for the {Name, Properties} palette-entry shape (same
 * encoding vanilla structure blocks use), and LitematicaBitPack for the one part that's genuinely
 * Litematica-specific: how BlockStates packs palette indices into the LongArray.
 */
public final class LitematicaImporter {

    private LitematicaImporter() {}

    public static List<StructFile.BlockEntry> importFile(Path path) throws IOException {
        CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
        HolderGetter<Block> blocks = Minecraft.getInstance().level.registryAccess().lookupOrThrow(Registries.BLOCK);

        List<StructFile.BlockEntry> entries = new ArrayList<>();
        CompoundTag regions = root.getCompoundOrEmpty("Regions");
        for (String regionName : regions.keySet()) {
            CompoundTag region = regions.getCompoundOrEmpty(regionName);
            readRegion(region, blocks, entries);
        }
        normalizeToZeroOrigin(entries);
        return entries;
    }

    private static void readRegion(CompoundTag region, HolderGetter<Block> blocks, List<StructFile.BlockEntry> out) {
        CompoundTag posTag = region.getCompoundOrEmpty("Position");
        CompoundTag sizeTag = region.getCompoundOrEmpty("Size");
        int posX = posTag.getIntOr("x", 0), posY = posTag.getIntOr("y", 0), posZ = posTag.getIntOr("z", 0);
        int sizeX = sizeTag.getIntOr("x", 0), sizeY = sizeTag.getIntOr("y", 0), sizeZ = sizeTag.getIntOr("z", 0);

        // Size can be negative -- the region then actually extends from Position DOWN to
        // Position+Size+1, not up. minX/Y/Z here is the true minimum corner regardless of sign.
        int minX = sizeX < 0 ? posX + sizeX + 1 : posX;
        int minY = sizeY < 0 ? posY + sizeY + 1 : posY;
        int minZ = sizeZ < 0 ? posZ + sizeZ + 1 : posZ;
        int countX = Math.abs(sizeX), countY = Math.abs(sizeY), countZ = Math.abs(sizeZ);
        if (countX == 0 || countY == 0 || countZ == 0) return;

        ListTag paletteTag = region.getListOrEmpty("BlockStatePalette");
        List<BlockState> palette = new ArrayList<>(paletteTag.size());
        for (int i = 0; i < paletteTag.size(); i++) {
            palette.add(NbtUtils.readBlockState(blocks, paletteTag.getCompoundOrEmpty(i)));
        }
        if (palette.isEmpty()) return;

        long[] packed = region.getLongArray("BlockStates").orElse(new long[0]);
        int bitsPerEntry = LitematicaBitPack.bitsPerEntry(palette.size());
        long volume = (long) countX * countY * countZ;

        for (long index = 0; index < volume; index++) {
            int paletteIndex = (int) LitematicaBitPack.get(packed, bitsPerEntry, index);
            if (paletteIndex < 0 || paletteIndex >= palette.size()) continue;
            BlockState state = palette.get(paletteIndex);
            if (state.isAir()) continue;

            int x = (int) (index % countX);
            int y = (int) (index / ((long) countX * countZ));
            int z = (int) ((index / countX) % countZ);

            StructFile.BlockEntry entry = new StructFile.BlockEntry();
            entry.x = minX + x;
            entry.y = minY + y;
            entry.z = minZ + z;
            entry.block = BlockStateCodec.id(state);
            entry.properties = BlockStateCodec.properties(state);
            out.add(entry);
        }
    }

    private static void normalizeToZeroOrigin(List<StructFile.BlockEntry> entries) {
        if (entries.isEmpty()) return;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (StructFile.BlockEntry e : entries) {
            minX = Math.min(minX, e.x);
            minY = Math.min(minY, e.y);
            minZ = Math.min(minZ, e.z);
        }
        for (StructFile.BlockEntry e : entries) {
            e.x -= minX;
            e.y -= minY;
            e.z -= minZ;
        }
    }
}
