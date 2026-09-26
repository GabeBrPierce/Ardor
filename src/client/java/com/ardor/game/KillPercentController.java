package com.ardor.game;

import com.ardor.client.StatusIndicator;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

/**
 * "Kill % [Entity]" / "Kill % Hostile|Passive" from the entity sub-wheel -- snapshots however many
 * matching, alive entities exist within RADIUS right now, computes ceil(total * percent / 100), and
 * kills that many (nearest-first, one at a time via GameActionController.attackEntityUntilDead,
 * same shared attack loop KillAllController already uses). The target count is fixed at start --
 * this does NOT continuously re-percentage against a growing/shrinking population, so "50% of the 10
 * zombies here now" always means 5, even if 3 more spawn mid-run.
 *
 * Same level of rigor as KillAllController (which this is deliberately modeled on): a "kill" is
 * counted once GameActionController stops being busy after being pointed at a target, not from a
 * confirmed death event -- there is no such event to hook client-side. A target that flees out of
 * range still counts as "handled" the same way KillAllController's own re-scan loop already treats
 * that case.
 */
public final class KillPercentController {

    private static final double RADIUS = 24.0;

    private static java.util.function.Predicate<Entity> filter;
    private static int targetCount;
    private static int handledCount;
    private static boolean awaitingFirst;
    private static volatile boolean active;
    private static boolean tickerRegistered;

    private KillPercentController() {}

    public static void startByType(EntityType<?> type, String name, int percent) {
        begin(e -> e.getType() == type, percent, name);
    }

    public static void startByCategory(String category, int percent) {
        begin(categoryPredicate(category), percent, category);
    }

    private static java.util.function.Predicate<Entity> categoryPredicate(String category) {
        return switch (category) {
            case "hostile" -> e -> e instanceof Monster;
            case "passive" -> e -> e instanceof Mob && !(e instanceof Monster);
            default -> throw new IllegalArgumentException("Unknown category: " + category);
        };
    }

    private static void begin(java.util.function.Predicate<Entity> matcher, int percent, String label) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.level == null) return;

        AABB area = player.getBoundingBox().inflate(RADIUS);
        int total = (int) client.level.getEntities(player, area, e -> e.isAlive() && matcher.test(e)).stream().count();
        int target = (int) Math.ceil(total * percent / 100.0);
        if (target <= 0) {
            StatusIndicator.show("No " + label + " nearby to kill.");
            return;
        }

        filter = matcher;
        targetCount = target;
        handledCount = 0;
        awaitingFirst = true;
        active = true;
        ensureTicker();
        StatusIndicator.show("Killing " + target + " of " + total + " (" + percent + "% " + label + ")");
    }

    public static void stop() {
        active = false;
    }

    public static boolean isActive() {
        return active;
    }

    private static void ensureTicker() {
        if (tickerRegistered) return;
        tickerRegistered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick(client);
            } catch (RuntimeException e) {
                System.err.println("[ardor] kill percent failed: " + e);
                active = false;
            }
        });
    }

    private static void tick(Minecraft client) {
        if (!active) return;
        if (GameActionController.isBusy()) return;

        if (awaitingFirst) {
            awaitingFirst = false;
        } else {
            handledCount++;
            if (handledCount >= targetCount) {
                active = false;
                StatusIndicator.show("Kill % done -- handled " + handledCount);
                return;
            }
        }

        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            active = false;
            return;
        }

        AABB area = player.getBoundingBox().inflate(RADIUS);
        Entity nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (Entity candidate : client.level.getEntities(player, area, e -> e.isAlive() && filter.test(e))) {
            double distSq = candidate.distanceToSqr(player);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = candidate;
            }
        }

        if (nearest == null) {
            active = false; // nothing left matching -- done early
            return;
        }
        GameActionController.attackEntityUntilDead(nearest);
    }
}
