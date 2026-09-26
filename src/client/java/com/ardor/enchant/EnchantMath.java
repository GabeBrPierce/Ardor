package com.ardor.enchant;

/**
 * java.util.Random/RandomSource-compatible 48-bit LCG math. Used only to recover the player's
 * hidden persistent enchantment-random state from two observed EnchantmentMenu.getEnchantmentSeed()
 * outputs -- everything else (advancing state, drawing costs/enchantments) goes through the real
 * net.minecraft.util.RandomSource so prediction always matches the live game exactly.
 */
final class EnchantMath {

    static final long MULT = 0x5DEECE66DL;
    static final long ADD = 0xBL;
    static final long MASK = (1L << 48) - 1;
    private static final long HIGH32_MASK = 0x0000_FFFF_FFFF_0000L;

    private EnchantMath() {}

    static long step(long state) {
        return (state * MULT + ADD) & MASK;
    }

    record GapMatch(int gap, long state) {}

    /**
     * Generalized recoverStateAfter: obs1/obs2 are two getEnchantmentSeed() values read from two
     * real enchant completions on the same table session, but the number of player.random.nextInt()
     * calls between them isn't assumed to be exactly 1 -- some servers run a skills/RPG plugin that
     * draws extra randomness as part of its own enchant-completion handling (confirmed live: a
     * "Lucky Table" ability on one such server), so vanilla's "one completion = one step" isn't
     * always true. Tries gap sizes 1..maxGap and returns the first (gap, state-after-obs2) that
     * resolves, or null if none do within that range. A `low` candidate is checked at increasing
     * gaps cumulatively (one step() per gap per low, not recomputed from scratch), so this costs the
     * same order of work as the old exact-1 search times maxGap, not more.
     */
    static GapMatch recoverStateAfterGap(int obs1, int obs2, int maxGap) {
        long obs1High = ((long) obs1) << 16 & HIGH32_MASK;
        long obs2High = ((long) obs2) << 16 & HIGH32_MASK;
        for (long low = 0; low < 65536; low++) {
            long state = obs1High | low;
            for (int gap = 1; gap <= maxGap; gap++) {
                state = step(state);
                if ((state & HIGH32_MASK) == obs2High) {
                    return new GapMatch(gap, state & MASK);
                }
            }
        }
        return null;
    }

    /**
     * obs1/obs2 are two getEnchantmentSeed() values read from two consecutive real enchant
     * completions on the same table session (nothing else may consume the player's random source
     * in between). Recovers the full 48-bit internal state immediately after obs2 was produced.
     */
    static long recoverStateAfter(int obs1, int obs2) {
        GapMatch match = recoverStateAfterGap(obs1, obs2, 1);
        if (match == null) {
            throw new IllegalStateException("enchant seed recovery: no low-16-bit match for observations " + obs1 + ", " + obs2);
        }
        return match.state();
    }

    /** RandomSource.create(x)/setSeed(x) sets internal state = (x ^ MULT) & MASK; passing (state ^ MULT) makes it start at exactly `state`. */
    static long seedArgFor(long internalState) {
        return internalState ^ MULT;
    }
}
