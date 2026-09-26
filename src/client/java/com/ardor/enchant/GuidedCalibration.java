package com.ardor.enchant;

import com.ardor.client.PlayerTaskBoard;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Same observation-based recovery EnchantOrderTask.calibrate automates, except the player performs
 * every click by hand on the REAL vanilla EnchantmentScreen (this menu isn't rendered by anything of
 * ours -- EnchantRequestScreen is a plain Screen with no slot widgets at all, so there's nothing to
 * click there; this hands the menu to vanilla's own screen instead, see
 * EnchantRequestScreen.startGuidedCalibration) while this class just watches the menu/inventory each
 * tick and narrates over chat and the on-screen checklist (PlayerTaskBoard). Nothing but the
 * player's own deliberate action can draw from the shared LivingEntity random between observations
 * that way -- automated calibration can't guarantee that (damage, eating, durability loss, potion
 * ticks, XP orb pickup, etc. all draw from the same random), and this server ALSO runs a custom
 * enchanting skill plugin (a "Lucky Table" level-upgrade ability observed live) that could
 * independently draw extra randomness on top of vanilla's own.
 *
 * Takes THREE pre-throw dummy-enchant observations instead of two, for two independent reasons
 * confirmed live rather than assumed:
 *   1. Tolerating one-off interference -- if the enchantmentSeed transition between two of them
 *      doesn't resolve to a valid LCG step at all, something drew extra randomness in that specific
 *      gap, and the OTHER pair is used instead of failing the whole sequence outright.
 *   2. This server's enchant completions don't cost exactly vanilla's one player.random.nextInt()
 *      call -- confirmed live (100% reproducible, not occasional bad luck: failed twice in a row,
 *      the second time within 8 seconds of starting, far too fast for "the player did something
 *      else in between" to explain). A skills/RPG plugin doing its own extra rolls on top of
 *      vanilla's enchant handling is the working theory (this server has a "Lucky Table"
 *      level-upgrade ability observed live), but the exact cause doesn't matter -- what matters is
 *      the ACTUAL number of steps per completion, discovered empirically per world just like
 *      dropAdvanceSteps already is, via EnchantMath.recoverStateAfterGap trying gap sizes 1..16
 *      instead of assuming exactly 1. Comparing the gap found between obsA/obsB against the gap
 *      between obsB/obsC (chooseObservationPair) additionally confirms whether that per-completion
 *      cost is a FIXED constant (both agree) worth trusting going forward, not just a one-off.
 */
public final class GuidedCalibration {

    private static final Item JUNK_ITEM = Items.COBBLESTONE;
    private static final int THROWS_NEEDED = EnchantOrderSession.CALIBRATION_THROWS;
    private static final String PREFIX = "[Ardor Enchant Calculator] ";
    private static final int MIN_BOOKS = 4;

    private enum Phase { ENCHANT_1, ENCHANT_2, ENCHANT_3, THROWS, ENCHANT_4 }

    private final EnchantmentMenu menu;
    private final String profileKey;
    private final Runnable onDone;
    private final Consumer<String> onFailed;

    private Phase phase = Phase.ENCHANT_1;
    private int obsA;
    private int obsB;
    private int obsC;
    private long chosenState;
    private int chosenCompletionSteps;
    private int dropBaseline;
    private int dropped;
    private boolean finished = false;
    private boolean seenEmptySincePhaseStart = false;

    public GuidedCalibration(EnchantmentMenu menu, String profileKey, Runnable onDone, Consumer<String> onFailed) {
        this.menu = menu;
        this.profileKey = profileKey;
        this.onDone = onDone;
        this.onFailed = onFailed;
    }

    /** Null if prerequisites are met; otherwise a message explaining what's missing, to show before starting rather than discovering it mid-sequence. */
    public static String checkPrereqs() {
        Inventory inv = Minecraft.getInstance().player.getInventory();
        int books = countMatching(inv, Items.BOOK, true);
        int junk = countMatching(inv, JUNK_ITEM, false);
        if (books < MIN_BOOKS) return "Need at least " + MIN_BOOKS + " plain books in your inventory (have " + books + ").";
        if (junk < THROWS_NEEDED) {
            return "Need at least " + THROWS_NEEDED + " " + junkName() + " in your inventory (have " + junk + ").";
        }
        List<String> nearby = nearbyInterferingEntities();
        if (!nearby.isEmpty()) {
            return "Move away from nearby entities first (" + String.join(", ", nearby) + ") -- confirmed live via "
                    + "javap on Player.aiStep: standing near ANY mob or dropped item burns extra randomness from the "
                    + "same source enchantmentSeed comes from, every tick it stays in range, which breaks calibration.";
        }
        return null;
    }

    /**
     * Confirmed via javap on Player.aiStep: it scans this exact box (the player's own hitbox
     * inflated by 1.0/0.5/1.0, the "item pickup" check) every tick, and calls
     * Util.getRandom(list, this.random) -- burning one draw from the SAME random enchantmentSeed
     * comes from -- whenever ANY non-experience-orb entity is inside it. That draw never touches
     * enchantmentSeed itself, so it's invisible to a diagnostic that only watches enchantmentSeed
     * change (confirmed the hard way: three separate calibration failures, in both singleplayer and
     * on a modded server, that a per-tick seed-change log showed had NO extra seed changes at all --
     * the interference was real but happening somewhere this class couldn't see until this check was
     * added). A leftover dropped item from an earlier attempt, or a passive mob just wandering
     * close, is enough.
     */
    private static List<String> nearbyInterferingEntities() {
        LocalPlayer player = Minecraft.getInstance().player;
        Level level = Minecraft.getInstance().level;
        AABB box = player.getBoundingBox().inflate(1.0, 0.5, 1.0);
        List<String> names = new ArrayList<>();
        for (Entity e : level.getEntities(player, box)) {
            if (e.getType() == EntityTypes.EXPERIENCE_ORB) continue;
            names.add(e.getName().getString());
        }
        return names;
    }

    /**
     * Sends the first instruction, populates the on-screen checklist (PlayerTaskBoard -- chat alone
     * isn't a reliable channel for a step the player must actually act on if they're not watching
     * chat), and starts watching. Registers one persistent tick listener (Fabric's tick API has no
     * unregister -- same one-shot-that-never-fires-again pattern TickPoll uses; a finished
     * GuidedCalibration just costs one no-op check per tick for the rest of the session, same
     * bounded cost TaskRunner/GameActionController's own persistent tickers already accept).
     */
    public void start() {
        PlayerTaskBoard.start("Guided Calibration", allStepTexts());
        announce("Guided Calibration started. Follow each step exactly -- don't take damage, eat, "
                + "swim, or let anything else happen to your character in between, or the whole "
                + "thing has to restart from step 1. Also stay away from other mobs/players and "
                + "leftover dropped items -- anything within about 2 blocks of you burns randomness "
                + "the same way (confirmed via Player.aiStep's own item-pickup scan).");
        announce(stepText());
        lastLoggedSeed = menu.getEnchantmentSeed();
        System.out.println("[ardor] guided calibration diagnostic: tick=0 seed=" + lastLoggedSeed + " (initial)");
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    // Diagnostic only (see TODO.md 2026-09-19): logs every tick the live enchantmentSeed actually
    // changes, regardless of phase, so a real change with no corresponding click is visible directly
    // instead of inferred from failed recovery math. Removed once the root cause is confirmed.
    private int tickCounter = 0;
    private int lastLoggedSeed;

    private void logSeedChanges() {
        tickCounter++;
        int seed = menu.getEnchantmentSeed();
        if (seed != lastLoggedSeed) {
            ItemStack stack = menu.getSlot(EnchantOrderSession.ITEM_SLOT).getItem();
            System.out.println("[ardor] guided calibration diagnostic: tick=" + tickCounter + " seed changed " + lastLoggedSeed + " -> " + seed
                    + " (slot=" + (stack.isEmpty() ? "empty" : stack.getItem() + (EnchantmentHelper.hasAnyEnchantments(stack) ? "+enchanted" : "")) + ", phase=" + phase + ")");
            lastLoggedSeed = seed;
        }
    }

    private boolean warnedThisPhase = false;

    private void tick() {
        if (finished) return;
        logSeedChanges();
        // Only warn during the dummy-enchant phases -- THROWS deliberately drops items right next to
        // the player, which is itself a "nearby entity" by this same check, but that's already
        // absorbed into dropAdvanceSteps' own empirical calibration rather than something to flag.
        if (phase != Phase.THROWS && !warnedThisPhase) {
            List<String> nearby = nearbyInterferingEntities();
            if (!nearby.isEmpty()) {
                warnedThisPhase = true;
                announce("Warning: " + String.join(", ", nearby) + " wandered close enough to interfere "
                        + "with this reading -- move away or wait for it to leave before continuing.");
            }
        }
        switch (phase) {
            case ENCHANT_1 -> watchForEnchant(obs -> { obsA = obs; advance(Phase.ENCHANT_2); });
            case ENCHANT_2 -> watchForEnchant(obs -> { obsB = obs; advance(Phase.ENCHANT_3); });
            case ENCHANT_3 -> watchForEnchant(obs -> { obsC = obs; chooseObservationPair(); });
            case THROWS -> watchThrows();
            case ENCHANT_4 -> watchForEnchant(this::finish);
        }
    }

    /**
     * The "outlier" logic, generalized to an unknown per-completion cost: recover the gap (not
     * assumed to be 1) for both (obsA,obsB) and (obsB,obsC). If both resolve AND agree on the same
     * gap, that's a fixed per-completion cost confirmed from two independent transitions -- use the
     * freshest state (after obsC) at maximum confidence. If only one pair resolves, use it. If both
     * resolve but DISAGREE, the per-completion cost isn't constant (a probabilistic effect, not a
     * flat one) -- use the fresher pair anyway as the best available guess; EnchantSeedTracker.
     * matchesLive will catch it later if that guess turns out wrong on the next order. Only give up
     * if neither pair resolves at all.
     */
    private void chooseObservationPair() {
        System.out.println("[ardor] guided calibration observations: obsA=" + obsA + " obsB=" + obsB + " obsC=" + obsC);
        EnchantMath.GapMatch ab = EnchantMath.recoverStateAfterGap(obsA, obsB, EnchantOrderSession.MAX_ENCHANT_COMPLETION_GAP);
        EnchantMath.GapMatch bc = EnchantMath.recoverStateAfterGap(obsB, obsC, EnchantOrderSession.MAX_ENCHANT_COMPLETION_GAP);

        if (ab != null && bc != null && ab.gap() == bc.gap()) {
            chosenState = bc.state();
            chosenCompletionSteps = bc.gap();
            announce("Each real enchant completion on this server costs " + bc.gap() + " step(s) of randomness "
                    + "(confirmed consistent across two completions).");
        } else if (bc != null) {
            chosenState = bc.state();
            chosenCompletionSteps = bc.gap();
            announce(ab != null
                    ? "Note: dummy enchants 1-2 and 2-3 disagreed on how much randomness one completion "
                        + "costs (" + ab.gap() + " vs " + bc.gap() + ") -- using enchants 2 and 3."
                    : "Note: the first two dummy enchants looked inconsistent -- using enchants 2 and 3 instead.");
        } else if (ab != null) {
            chosenState = advance(ab.state(), ab.gap()); // ab.state() is "after obsB" -- step forward by the same gap to reach "after obsC" for a fresh baseline
            chosenCompletionSteps = ab.gap();
            announce("Note: dummy enchants 2-3 looked inconsistent -- using enchants 1 and 2 instead.");
        } else {
            finished = true;
            PlayerTaskBoard.clear();
            String reason = "none of the dummy enchants gave a consistent reading within " + EnchantOrderSession.MAX_ENCHANT_COMPLETION_GAP
                    + " steps -- something interfered with all of them";
            announce("Calibration failed: " + reason + ". Please retry from step 1.");
            onFailed.accept(reason);
            return;
        }
        armThrows();
        advance(Phase.THROWS);
    }

    private static long advance(long state, int steps) {
        for (int i = 0; i < steps; i++) state = EnchantMath.step(state);
        return state;
    }

    private void advance(Phase next) {
        phase = next;
        seenEmptySincePhaseStart = false;
        warnedThisPhase = false;
        PlayerTaskBoard.advanceTo(next.ordinal());
        announce(stepText());
    }

    private static List<String> allStepTexts() {
        return Arrays.stream(Phase.values()).map(GuidedCalibration::stepText).toList();
    }

    private String stepText() {
        return stepText(phase);
    }

    private static String stepText(Phase phase) {
        return switch (phase) {
            case ENCHANT_1 -> "Step 1/5: Place a plain book in the table's item slot and click the "
                    + "top (cheapest) enchantment option.";
            case ENCHANT_2 -> "Step 2/5: Take the enchanted book back out. Place a second plain book "
                    + "in and complete another dummy enchant the same way.";
            case ENCHANT_3 -> "Step 3/5: Take the enchanted book back out. Place a third plain book "
                    + "in and complete one more dummy enchant the same way.";
            case THROWS -> "Step 4/5: Select " + junkName() + " in your hotbar (Q only drops "
                    + "whatever's currently selected!), then press Q " + THROWS_NEEDED + " times to "
                    + "drop them one at a time. Don't pick anything up in between.";
            case ENCHANT_4 -> "Step 5/5: Take whatever's in the item slot back out. Place a fourth "
                    + "plain book in and complete one final dummy enchant.";
        };
    }

    /**
     * Confirmed via javap on EnchantmentMenu.clickMenuButton/Player.onEnchantmentPerformed: a real
     * completed enchant is the ONLY thing that advances enchantmentSeed (one player.random.nextInt()
     * call), and the just-enchanted item is left sitting in the slot afterward -- it does not move
     * anywhere or get cleared automatically. That leftover result from the PREVIOUS step is still
     * there the instant this phase starts, so checking "is there an enchanted item here" without
     * first requiring the slot to have gone empty reads the SAME stale seed twice (confirmed live:
     * two "different" observations came back identical). Only accept an enchanted stack as a real
     * observation once the slot has been seen empty at some point since this phase began.
     */
    private void watchForEnchant(IntConsumer onObserved) {
        ItemStack stack = menu.getSlot(EnchantOrderSession.ITEM_SLOT).getItem();
        if (stack.isEmpty()) {
            seenEmptySincePhaseStart = true;
            return;
        }
        if (!seenEmptySincePhaseStart || !EnchantmentHelper.hasAnyEnchantments(stack)) return;
        onObserved.accept(menu.getEnchantmentSeed());
    }

    private void armThrows() {
        dropBaseline = countMatching(Minecraft.getInstance().player.getInventory(), JUNK_ITEM, false);
        dropped = 0;
    }

    private void watchThrows() {
        int current = countMatching(Minecraft.getInstance().player.getInventory(), JUNK_ITEM, false);
        int newDropped = Math.max(dropped, dropBaseline - current);
        if (newDropped <= dropped) return;
        dropped = newDropped;
        announce("Dropped " + dropped + "/" + THROWS_NEEDED + ".");
        PlayerTaskBoard.setDetail("Dropped " + dropped + "/" + THROWS_NEEDED + ".");
        if (dropped >= THROWS_NEEDED) advance(Phase.ENCHANT_4);
    }

    private void finish(int finalObs) {
        finished = true;
        PlayerTaskBoard.clear();
        try {
            EnchantSeedTracker.recoverAndCalibrate(profileKey, chosenState, chosenCompletionSteps, THROWS_NEEDED, finalObs);
            announce("Calibrated! Reopening the calculator...");
            onDone.run();
        } catch (RuntimeException e) {
            announce("Calibration failed: " + e.getMessage());
            onFailed.accept(e.getMessage());
        }
    }

    private static void announce(String message) {
        Minecraft.getInstance().player.sendSystemMessage(Component.literal(PREFIX + message));
    }

    private static String junkName() {
        return new ItemStack(JUNK_ITEM).getHoverName().getString();
    }

    private static int countMatching(Inventory inv, Item item, boolean plainOnly) {
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || stack.getItem() != item) continue;
            if (plainOnly && EnchantmentHelper.hasAnyEnchantments(stack)) continue;
            total += stack.getCount();
        }
        return total;
    }
}
