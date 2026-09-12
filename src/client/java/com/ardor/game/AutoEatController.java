package com.ardor.game;

import java.util.function.ToDoubleFunction;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Always-on reflex, two independent triggers, same standalone tick-registered shape as
 * SocialGreetingController:
 *
 * 1. Reactive (emergency): hunger below FOOD_THRESHOLD -- eats whatever available food has the
 *    highest FoodProperties.nutrition(). Unchanged from before; fallback for genuine starvation
 *    risk.
 *
 * 2. Proactive (saturation top-off): user-requested -- "keep saturation in mind... when safe but
 *    detects either night time or incoming monsters, keep an eye on their saturation." Real
 *    vanilla mechanic behind this: natural health regen only happens once the food bar is at 18+,
 *    and regens faster the higher current saturation is, so topping up saturation before trouble
 *    starts is a real survival strategy distinct from just not starving. Fires when not busy, not
 *    in immediate danger (see below), hunger isn't already at FULL_FOOD_LEVEL, and either it's
 *    night (Level.isDarkOutside() -- the same real API SleepController already verified and uses
 *    for this MC version; see that file's javadoc for why the classic isNight()/getDayTime()
 *    accessors are gone) or a hostile is within the wider AWARENESS_RADIUS (early warning, before
 *    anything is actually close). Eats whichever available food has the highest
 *    FoodProperties.saturation() (not nutrition -- the point is refilling the saturation buffer).
 *
 * Both paths share one "don't eat mid-danger" gate: a hostile within DEFENSIVE_RADIUS skips eating
 * entirely for that tick, even if isBusy() is false -- a mob can be closing in before combat
 * actually starts, and the user was explicit that eating should never happen while defending.
 */
public final class AutoEatController {

    private AutoEatController() {}

    private static final int FOOD_THRESHOLD = 14; // out of 20 -- "getting hungry", not "starving"
    private static final int FULL_FOOD_LEVEL = 20; // nothing to gain from proactively eating at full hunger
    private static final double DEFENSIVE_RADIUS = 8.0; // hostile this close = actively dangerous, never eat
    private static final double AWARENESS_RADIUS = 18.0; // wider early-warning radius for "monsters incoming"
    private static final int EAT_COOLDOWN_TICKS = 40; // 2s -- vanilla eating takes ~32 ticks

    private static int cooldownTicksLeft;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(AutoEatController::onTick);
    }

    private static void onTick(Minecraft client) {
        try {
            tickInner(client);
        } catch (RuntimeException e) {
            System.err.println("[ardor] auto-eat failed: " + e);
        }
    }

    private static void tickInner(Minecraft client) {
        if (cooldownTicksLeft > 0) {
            cooldownTicksLeft--;
            return;
        }

        LocalPlayer player = client.player;
        Level level = client.level;
        if (player == null || level == null) return;
        if (GameActionController.isBusy()) return;
        if (nearestHostileWithin(DEFENSIVE_RADIUS) != null) return; // in immediate danger -- never eat

        int foodLevel = player.getFoodData().getFoodLevel();
        Inventory inv = player.getInventory();

        if (foodLevel < FOOD_THRESHOLD) {
            if (eatBest(player, inv, FoodProperties::nutrition)) cooldownTicksLeft = EAT_COOLDOWN_TICKS;
            return;
        }

        boolean nightOrIncoming = level.isDarkOutside() || nearestHostileWithin(AWARENESS_RADIUS) != null;
        if (foodLevel < FULL_FOOD_LEVEL && nightOrIncoming) {
            if (eatBest(player, inv, FoodProperties::saturation)) cooldownTicksLeft = EAT_COOLDOWN_TICKS;
        }
    }

    private static Entity nearestHostileWithin(double radius) {
        return SelectorResolver.resolveOne("@e[category=hostile,distance=" + radius + ",sort=nearest,limit=1]");
    }

    /** Eats whichever inventory food item scores highest under scorer. Returns whether it ate. */
    private static boolean eatBest(LocalPlayer player, Inventory inv, ToDoubleFunction<FoodProperties> scorer) {
        int bestSlot = -1;
        double bestScore = -1;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null) continue;
            double score = scorer.applyAsDouble(food);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        if (bestSlot < 0) return false;

        selectSlot(inv, bestSlot);
        Minecraft.getInstance().gameMode.useItem(player, InteractionHand.MAIN_HAND);
        return true;
    }

    /** Mirrors GameActionController.selectItemInHand, but from a slot index we've already found. */
    private static void selectSlot(Inventory inv, int slotIndex) {
        if (Inventory.isHotbarSlot(slotIndex)) {
            inv.setSelectedSlot(slotIndex);
            return;
        }
        int hotbar = inv.getSelectedSlot();
        ItemStack held = inv.getItem(hotbar);
        ItemStack toEquip = inv.getItem(slotIndex);
        inv.setItem(hotbar, toEquip);
        inv.setItem(slotIndex, held);
    }
}
