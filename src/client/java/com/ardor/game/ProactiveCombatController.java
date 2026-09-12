package com.ardor.game;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * "If a mob is within line of sight of the player and is hostile towards the player we should
 * attempt to strike first. If there are >1 mobs approaching then we attack the closest one."
 * The existing defend-on-attack reflex (RegionManager.ensureDefaultDefendBinding) only fights
 * back AFTER the bot has already been hit. This is the proactive counterpart: scans for the
 * nearest visible hostile and engages it before it lands a hit. Runs alongside, not instead of,
 * that reactive binding -- this stays a fallback for whatever spawns too close to notice in time.
 *
 * Same always-on tick-check shape as GrindModeController/AutoFleeController: own END_CLIENT_TICK
 * hook, try/catch-log-never-crash.
 */
public final class ProactiveCombatController {

    private ProactiveCombatController() {}

    // Wider than the old reactive binding's 8-block radius (see RegionManager) since "strike
    // first" means noticing threats a bit further out, not absurdly far.
    private static final double ENGAGEMENT_RANGE = 12.0;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick(client);
            } catch (RuntimeException e) {
                System.err.println("[ardor] proactive combat failed: " + e);
            }
        });
    }

    private static void tick(Minecraft client) {
        // GameActionController.isBusy() covers "already attacking this (or any) target" -- the
        // shared attackUntilDead ticker just retargets in place on a repeat call, and isBusy()
        // stays true for the whole fight, so there's no separate "already engaged" state to track
        // here.
        if (GameActionController.isBusy()) return;
        LocalPlayer self = client.player;
        if (self == null || client.level == null) return;

        // sort=nearest already returns the closest match -- "attack the closest one when several
        // are approaching" falls out of this call for free.
        Entity target = SelectorResolver.resolveOne(
                "@e[category=hostile,distance=" + ENGAGEMENT_RANGE + ",sort=nearest,limit=1]");
        if (target == null || !target.isAlive()) return;
        if (!hasLineOfSight(self, target)) return;

        GameActionController.attackEntityUntilDead(target);
    }

    /**
     * Level.clip(ClipContext), confirmed via javap against the real jar (BlockGetter.clip,
     * inherited by Level/ClientLevel): a block-only raycast from eye to eye. ClipContext.Block.
     * COLLIDER checks real collision shapes (glass panes, leaves, etc. block sight the way a
     * player's eye actually would); Fluid.NONE means water doesn't block sight. The five-arg
     * constructor taking an Entity (rather than a raw CollisionContext) is the standard vanilla
     * shape for "raycast as if this entity were looking" and lets us pass the player directly.
     * HitResult.Type.MISS means nothing solid was in the way -- clear line of sight.
     */
    private static boolean hasLineOfSight(LocalPlayer self, Entity target) {
        Vec3 from = self.getEyePosition();
        Vec3 to = target.getEyePosition();
        ClipContext ctx = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self);
        BlockHitResult hit = self.level().clip(ctx);
        return hit.getType() == HitResult.Type.MISS;
    }
}
