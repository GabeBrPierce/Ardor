package com.ardor.enchant;

import net.minecraft.util.RandomSource;

/**
 * Tracks the player's hidden persistent enchantment-random state for one world/profile, recovered
 * from real observed EnchantmentMenu.getEnchantmentSeed() values (see EnchantMath) and calibrated
 * against however many raw RNG advances one item-drop reroll AND one real enchant completion each
 * actually burn on this server -- neither is hardcoded. Vanilla is 1 step per completion, but a
 * server-side skill/RPG plugin can draw extra randomness as part of its own enchant handling
 * (confirmed live -- see TODO.md), so callers discover the real per-completion cost themselves
 * (EnchantMath.recoverStateAfterGap) before calling recoverAndCalibrate here.
 */
public final class EnchantSeedTracker {

    private static final int MAX_CALIBRATION_STEPS = 64;

    private final String profileKey;
    private EnchantSeedState state;

    private EnchantSeedTracker(String profileKey, EnchantSeedState state) {
        this.profileKey = profileKey;
        this.state = state;
    }

    public static java.util.Optional<EnchantSeedTracker> load(String profileKey) {
        return EnchantSeedStore.get().get(profileKey).map(s -> new EnchantSeedTracker(profileKey, s));
    }

    /**
     * `stateAfterLastObs`/`enchantCompletionSteps` are the already-validated result of recovering
     * two real enchant-completion observations (see EnchantMath.recoverStateAfterGap) -- the caller
     * owns how robustly that recovery was done (GuidedCalibration cross-checks three observations
     * for a consistent gap; EnchantOrderTask.calibrate's automated path just takes two). From there,
     * calibrationThrows/finalObs come from doing that many drop/pickup reroll cycles followed by one
     * more real enchant completion, read immediately after -- this calibrates the drop-reroll cost
     * against the now-known completion cost and produces the tracker.
     */
    public static EnchantSeedTracker recoverAndCalibrate(String profileKey, long stateAfterLastObs, int enchantCompletionSteps, int calibrationThrows, int finalObs) {
        int dropAdvanceSteps = calibrateDropSteps(stateAfterLastObs, enchantCompletionSteps, calibrationThrows, finalObs);
        // calibration itself was `calibrationThrows` drop cycles + exactly one more real completion (which produced finalObs).
        long stateAfterCalibration = advance(stateAfterLastObs, calibrationThrows * dropAdvanceSteps + enchantCompletionSteps);
        EnchantSeedState newState = new EnchantSeedState(stateAfterCalibration, dropAdvanceSteps, enchantCompletionSteps);
        EnchantSeedStore.get().put(profileKey, newState);
        return new EnchantSeedTracker(profileKey, newState);
    }

    private static long advance(long state, int steps) {
        for (int i = 0; i < steps; i++) state = EnchantMath.step(state);
        return state;
    }

    private static int calibrateDropSteps(long stateAfterLastObs, int enchantCompletionSteps, int calibrationThrows, int finalObs) {
        if (calibrationThrows <= 0) throw new IllegalArgumentException("calibrationThrows must be > 0");
        for (int steps = 0; steps < MAX_CALIBRATION_STEPS; steps++) {
            if (seedAfter(stateAfterLastObs, calibrationThrows, steps, enchantCompletionSteps) == finalObs) return steps;
        }
        throw new IllegalStateException("enchant reroll calibration: no drop-advance count in [0," + MAX_CALIBRATION_STEPS
                + ") reproduced the observed seed -- a real action (combat, another enchant, etc) probably happened during calibration");
    }

    public int currentEnchantmentSeed() {
        return state.currentEnchantmentSeed();
    }

    /**
     * Anything that draws from the player's own persistent random OUTSIDE of a tracked commit --
     * manually dropping/enchanting without going through Ardor, taking damage, eating, a server-side
     * skill plugin drawing extra randomness on an enchant completion (confirmed to exist on this
     * server, see TODO.md) -- silently desyncs this tracked state from the server's real one, since
     * nothing here can see untracked draws. This is the cheap way to catch that BEFORE trusting a
     * prediction: compare against the live value the server is actually showing right now.
     */
    public boolean matchesLive(int liveEnchantmentSeed) {
        return liveEnchantmentSeed == currentEnchantmentSeed();
    }

    /** Call once matchesLive() comes back false -- discards this world's calibration so the next table-open starts fresh instead of continuing to silently compute wrong predictions from a stale baseline. */
    public void invalidate() {
        EnchantSeedStore.get().remove(profileKey);
    }

    /** Peek only -- does not mutate tracked state. What enchantmentSeed would result from doing `throwCycles` drop/pickup rerolls then one real enchant completion. */
    public int predictSeedAfter(int throwCycles) {
        if (throwCycles < 0) return currentEnchantmentSeed();
        return seedAfter(state.internalState(), throwCycles, state.dropAdvanceSteps(), state.enchantCompletionSteps());
    }

    private static int seedAfter(long internalState, int throwCycles, int dropAdvanceSteps, int enchantCompletionSteps) {
        RandomSource rand = RandomSource.create(EnchantMath.seedArgFor(internalState));
        int steps = throwCycles * dropAdvanceSteps + enchantCompletionSteps;
        int result = 0;
        for (int i = 0; i < steps; i++) result = rand.nextInt();
        return result;
    }

    /** Call once the CURRENT seed's outcome (no throws/dummy) was actually taken from the table -- advances tracked state by the one real completion. */
    public void commitDirect() {
        advanceAndPersist(state.enchantCompletionSteps());
    }

    /** Call once `throwCycles` drop/pickup rerolls, one dummy enchant, and one real enchant have all actually happened in that order. */
    public void commitAfterThrows(int throwCycles) {
        advanceAndPersist(throwCycles * state.dropAdvanceSteps() + state.enchantCompletionSteps() * 2);
    }

    private void advanceAndPersist(int steps) {
        state = new EnchantSeedState(advance(state.internalState(), steps), state.dropAdvanceSteps(), state.enchantCompletionSteps());
        EnchantSeedStore.get().put(profileKey, state);
    }
}
