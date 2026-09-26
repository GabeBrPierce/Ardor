package com.ardor.enchant;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;

import java.util.List;
import java.util.Map;

/**
 * Searches reroll counts (via EnchantSeedTracker's prediction) for one that makes some table slot
 * offer exactly the wanted enchantment set on `item`, at a fixed (already-placed) bookshelf count.
 * v1 does not vary bookshelf count -- if the goal needs a different count than what's currently
 * placed, this reports unreachable rather than placing/breaking shelves (see TODO.md).
 */
public final class EnchantOrderPlanner {

    public record Plan(int throwCycles, int slot, int cost) {}

    public static final int MAX_THROW_CYCLES = 8192;
    /** Small bound used when populating the picker UI for every enchantment/level at once -- keeps item selection responsive at the cost of missing far-out reroll possibilities (a real order still searches the full MAX_THROW_CYCLES). */
    public static final int QUICK_CHECK_THROW_CYCLES = 256;

    private EnchantOrderPlanner() {}

    public static Plan search(RegistryAccess registryAccess, EnchantSeedTracker tracker, ItemStack item, int bookshelves, Map<Holder<Enchantment>, Integer> wanted) {
        return search(registryAccess, tracker, item, bookshelves, wanted, MAX_THROW_CYCLES);
    }

    public static Plan search(RegistryAccess registryAccess, EnchantSeedTracker tracker, ItemStack item, int bookshelves, Map<Holder<Enchantment>, Integer> wanted, int maxThrowCycles) {
        for (int throwCycles = -1; throwCycles <= maxThrowCycles; throwCycles++) {
            int seed = throwCycles < 0 ? tracker.currentEnchantmentSeed() : tracker.predictSeedAfter(throwCycles);
            Plan plan = matchingSlot(registryAccess, seed, bookshelves, item, wanted, throwCycles);
            if (plan != null) return plan;
        }
        return null;
    }

    private static Plan matchingSlot(RegistryAccess registryAccess, int enchantmentSeed, int bookshelves, ItemStack item, Map<Holder<Enchantment>, Integer> wanted, int throwCycles) {
        int[] costs = EnchantSimulator.costsFor(enchantmentSeed, bookshelves, item);
        for (int slot = 0; slot < 3; slot++) {
            if (costs[slot] <= 0) continue;
            List<EnchantmentInstance> offered = EnchantSimulator.enchantmentsForSlot(registryAccess, enchantmentSeed, slot, costs[slot], item);
            if (matches(offered, wanted)) return new Plan(throwCycles, slot, costs[slot]);
        }
        return null;
    }

    private static boolean matches(List<EnchantmentInstance> offered, Map<Holder<Enchantment>, Integer> wanted) {
        if (offered.size() != wanted.size()) return false;
        for (EnchantmentInstance inst : offered) {
            Integer level = wanted.get(inst.enchantment());
            if (level == null || level != inst.level()) return false;
        }
        return true;
    }
}
