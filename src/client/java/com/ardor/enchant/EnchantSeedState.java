package com.ardor.enchant;

/**
 * Recovered/tracked hidden state of the player's persistent enchantment random, plus how many raw
 * LCG steps one item-drop reroll burns AND how many a real completed enchant burns -- neither is a
 * fixed game constant we can assume (vanilla is 1 step per completion, but a server-side skill/RPG
 * plugin can draw extra randomness as part of its own enchant handling -- confirmed live), both
 * calibrated empirically per world (see EnchantSeedTracker).
 */
public record EnchantSeedState(long internalState, int dropAdvanceSteps, int enchantCompletionSteps) {

    public int currentEnchantmentSeed() {
        return (int) (internalState >>> 16);
    }
}
