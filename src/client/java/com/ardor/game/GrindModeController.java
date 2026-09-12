package com.ardor.game;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * "Add a command wheel for things like 'Grind Mobs' where you would just sit there and attack
 * mobs that would show up at where you were looking. So when a mob shows up there you attack
 * it." A standing AFK-farm mode: does nothing but watch the crosshair (the same cached per-frame
 * hitResult SingleSelectionMode reuses) and, whenever a hostile mob is directly in view and nothing else is
 * already being fought, attacks it via the existing bell-curve attackUntilDead loop. No movement,
 * no target searching -- matches "just sit there," aiming stays entirely up to wherever the
 * player (or whatever's driving look.at) is already facing.
 */
public final class GrindModeController {

    private GrindModeController() {}

    private static volatile boolean active;
    private static boolean tickerRegistered;

    public static void start() {
        active = true;
        ensureTicker();
    }

    public static void stop() {
        active = false;
        GameActionController.stopAttacking();
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
                System.err.println("[ardor] grind mode failed: " + e);
            }
        });
    }

    private static void tick(Minecraft client) {
        if (!active || GameActionController.isBusy()) return;
        HitResult hit = client.hitResult;
        if (hit == null || hit.getType() != HitResult.Type.ENTITY) return;
        if (!(hit instanceof EntityHitResult entityHit)) return;
        Entity target = entityHit.getEntity();
        if (target instanceof Monster && target.isAlive()) {
            GameActionController.attackEntityUntilDead(target);
        }
    }
}
