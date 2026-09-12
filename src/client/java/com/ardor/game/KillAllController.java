package com.ardor.game;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

import java.util.function.Predicate;

/**
 * "Kill All" from SingleSelectionMode's entity sub-wheel (start(EntityType)) and "Kill Hostile
 * Mobs" from the Area Selection follow-up wheel (startHostilesInArea(AABB)) -- both are the same
 * underlying loop: re-scan for the nearest ALIVE entity matching a filter, feed it to
 * GameActionController.attackEntityUntilDead (a single shared attack loop), and only look for the
 * next one once GameActionController.isBusy() clears. The scan area is either a radius around the
 * player (entity sub-wheel: RADIUS, recomputed every tick since the player moves) or a fixed AABB
 * (area wheel: whatever Area Selection captured) -- fixedArea null means "use the radius."
 */
public final class KillAllController {

    private static final double RADIUS = 24.0;

    private static Predicate<Entity> filter;
    private static AABB fixedArea; // null = radius-around-player mode
    private static volatile boolean active;
    private static boolean tickerRegistered;

    private KillAllController() {}

    /** Entity sub-wheel's "Kill All" -- every alive entity of this exact type within RADIUS of the player. */
    public static void start(EntityType<?> type) {
        begin(e -> e.getType() == type, null);
    }

    /** Area Selection's "Kill Hostile Mobs" -- every alive hostile (Monster) within the given area. */
    public static void startHostilesInArea(AABB area) {
        begin(e -> e instanceof Monster, area);
    }

    private static void begin(Predicate<Entity> newFilter, AABB newFixedArea) {
        filter = newFilter;
        fixedArea = newFixedArea;
        active = true;
        ensureTicker();
    }

    public static void stop() {
        active = false;
        filter = null;
        fixedArea = null;
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
                System.err.println("[ardor] kill all failed: " + e);
                active = false;
            }
        });
    }

    private static void tick(Minecraft client) {
        if (!active) return;
        if (GameActionController.isBusy()) return; // still fighting the current target

        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            active = false;
            return;
        }

        AABB area = fixedArea != null ? fixedArea : player.getBoundingBox().inflate(RADIUS);
        Entity nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (Entity candidate : client.level.getEntities(player, area,
                e -> e.isAlive() && filter.test(e))) {
            double distSq = candidate.distanceToSqr(player);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = candidate;
            }
        }

        if (nearest == null) {
            active = false; // nothing left in range
            return;
        }
        GameActionController.attackEntityUntilDead(nearest);
    }
}
