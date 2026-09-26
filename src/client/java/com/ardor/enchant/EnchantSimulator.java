package com.ardor.enchant;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EnchantingTableBlock;

import java.util.List;

/**
 * Reproduces EnchantmentMenu's own private slotsChanged/getEnchantmentList logic exactly (verified
 * against the decompiled 26.1.2 client jar this project targets), so a prediction for a given
 * enchantmentSeed always matches what the real table would show/give for that seed -- no hand-
 * ported weight tables, just the real vanilla RandomSource + EnchantmentHelper.
 */
public final class EnchantSimulator {

    private EnchantSimulator() {}

    public static int[] costsFor(int enchantmentSeed, int bookshelves, ItemStack item) {
        RandomSource random = RandomSource.create();
        random.setSeed(enchantmentSeed);
        int[] costs = new int[3];
        for (int slot = 0; slot < 3; slot++) {
            int cost = EnchantmentHelper.getEnchantmentCost(random, slot, bookshelves, item);
            costs[slot] = cost < slot + 1 ? 0 : cost;
        }
        return costs;
    }

    public static List<EnchantmentInstance> enchantmentsForSlot(RegistryAccess registryAccess, int enchantmentSeed, int slot, int cost, ItemStack item) {
        if (cost <= 0) return List.of();
        var tableEnchants = registryAccess.lookupOrThrow(Registries.ENCHANTMENT).get(EnchantmentTags.IN_ENCHANTING_TABLE);
        if (tableEnchants.isEmpty()) return List.of();
        HolderSet.Named<Enchantment> named = tableEnchants.get();

        RandomSource random = RandomSource.create();
        random.setSeed((long) enchantmentSeed + slot);
        List<EnchantmentInstance> list = EnchantmentHelper.selectEnchantment(random, item, cost, named.stream());
        if (item.is(Items.BOOK) && list.size() > 1) {
            list.remove(random.nextInt(list.size()));
        }
        return list;
    }

    public static int countBookshelves(Level level, BlockPos tablePos) {
        int count = 0;
        for (BlockPos offset : EnchantingTableBlock.BOOKSHELF_OFFSETS) {
            if (EnchantingTableBlock.isValidBookShelf(level, tablePos, offset)) count++;
        }
        return count;
    }

    public static int maxBookshelves() {
        return EnchantingTableBlock.BOOKSHELF_OFFSETS.size();
    }
}
