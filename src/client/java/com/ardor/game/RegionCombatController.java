package com.ardor.game;

import com.ardor.client.ArdorMasterToggle;
import com.ardor.region.Aggressiveness;
import com.ardor.region.RegionManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Region Behavior Settings: how aggressively the bot implicitly targets Passive Mobs, Hostile
 * Mobs, and Players, each independently Off/Reactive/Proactive, inherited up the region hierarchy
 * (see Region's own doc and RegionManager.resolveAggressiveness). Replaces the old always-on
 * hostile-only ProactiveCombatController and the profile-wide Auto-Defend on/off toggle with one
 * unified, per-region, per-category system.
 *
 * "It's a mob grinder -- when mobs enter that area I want to attack those mobs. Rules should apply
 * to interactions WITHIN regions, not where the player is standing." Real design correction, not a
 * bug fix to a working feature: the first version resolved the setting at the PLAYER's own current
 * position and then searched for a nearby target -- so a small grinder region the player wasn't
 * standing inside of (the normal case: you stand next to/above a grinder, not crammed into its
 * kill box) never actually applied. Every candidate entity within range is now checked against
 * ITS OWN position's resolved region, not the player's -- a mob that wanders into a Proactive
 * region gets hunted regardless of where the player happens to be standing, exactly the grinder
 * use case. The search radius around the player is still there (nothing scans the whole loaded
 * world every tick), but it's just a search bound now, not the thing whose region gets checked.
 *
 * Off:       never implicitly targeted at all (an explicit user/task command still works regardless
 *            -- this only gates the two IMPLICIT reflexes below).
 * Reactive:  fight back against the nearest matching entity within REACT_RADIUS, checked against
 *            ITS OWN region, the tick the player takes damage. Same "can't identify the actual
 *            attacker, so guess nearest" approximation the old default-defend command already
 *            accepted (see TODO.md).
 * Proactive: continuously hunt the nearest entity within ENGAGEMENT_RANGE whose OWN region has this
 *            category set to Proactive, whenever not already fighting something.
 *
 * Own independent health-delta tracking (mirroring DomainEvents.checkClientDamage's exact shape)
 * rather than going through EventHookDispatcher/DomainEvents' generic event-to-task-text pipeline
 * -- that pipeline still exists unchanged for a user's own arbitrary "OnClientTakesDamage" binding
 * (EventConfigScreen), unrelated to and unaffected by this controller.
 */
public final class RegionCombatController {

    private RegionCombatController() {}

    private static final double ENGAGEMENT_RANGE = 12.0; // proactive hunt search radius, around the player
    private static final double REACT_RADIUS = 8.0; // reactive retaliation search radius -- matches the old default-defend command's distance=8

    private static float lastHealth = -1;

    private static final int PROACTIVE_SCAN_INTERVAL_TICKS = 5; // ~250ms -- combat reaction doesn't need tick-perfect precision, unlike the damage-triggered Reactive path below (left un-throttled)
    private static int proactiveScanTick;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick(client);
            } catch (RuntimeException e) {
                System.err.println("[ardor] region combat failed: " + e);
            }
        });
    }

    private static void tick(Minecraft client) {
        LocalPlayer self = client.player;
        if (!ArdorMasterToggle.isEnabled() || self == null || client.level == null) {
            lastHealth = -1;
            return;
        }

        checkReactive(client, self);

        if (GameActionController.isBusy()) return;
        if (++proactiveScanTick < PROACTIVE_SCAN_INTERVAL_TICKS) return;
        proactiveScanTick = 0;
        checkProactive(client, self);
    }

    private static void checkReactive(Minecraft client, LocalPlayer self) {
        float health = self.getHealth();
        boolean damaged = lastHealth >= 0 && health < lastHealth;
        lastHealth = health;
        if (!damaged || GameActionController.isBusy()) return;

        String profileKey = RegionManager.currentProfileKey();
        Entity target = findTarget(client, self, profileKey, REACT_RADIUS, agg -> agg != Aggressiveness.OFF);
        if (target != null) GameActionController.attackEntityUntilDead(target);
    }

    private static void checkProactive(Minecraft client, LocalPlayer self) {
        String profileKey = RegionManager.currentProfileKey();
        Entity target = findTarget(client, self, profileKey, ENGAGEMENT_RANGE, agg -> agg == Aggressiveness.PROACTIVE);
        if (target != null) GameActionController.attackEntityUntilDead(target);
    }

    private static final String[] CATEGORY_PRIORITY = {"hostile", "passive", "player"};

    /**
     * Category-priority target search: checks hostile, then passive, then player candidates in
     * that order and stops at the first category with a real (qualifying, line-of-sight) candidate
     * -- a passive-mobs=Reactive region taking damage from a zombie won't also take a swing at a
     * nearby cow, even if the cow is closer. Was: all nearby entities of every category pooled
     * together and sorted by distance alone, so a closer passive mob could beat a farther hostile
     * one even when only Hostile was enabled -- category never actually gated anything. Within a
     * category, nearest-first still applies. Aggressiveness is still resolved at each CANDIDATE'S
     * OWN position (not the player's) -- see class doc for why.
     */
    private static Entity findTarget(Minecraft client, LocalPlayer self, String profileKey, double radius, Predicate<Aggressiveness> qualifies) {
        ClientLevel level = client.level;
        AABB box = self.getBoundingBox().inflate(radius);
        List<Entity> nearby = level.getEntitiesOfClass(Entity.class, box, e -> e != self && e.isAlive());
        nearby.sort(Comparator.comparingDouble(e -> e.distanceToSqr(self)));

        for (String category : CATEGORY_PRIORITY) {
            Entity found = nearestInCategory(nearby, self, profileKey, category, qualifies);
            if (found != null) return found;
        }
        return null;
    }

    private static Entity nearestInCategory(List<Entity> nearbySorted, LocalPlayer self, String profileKey, String category, Predicate<Aggressiveness> qualifies) {
        for (Entity e : nearbySorted) {
            if (!category.equals(SelectorResolver.categoryOf(e))) continue;
            Aggressiveness agg = aggressivenessFor(profileKey, category, e.blockPosition());
            if (qualifies.test(agg) && LineOfSight.hasLineOfSight(self, e)) {
                return e;
            }
        }
        return null;
    }

    private static Aggressiveness aggressivenessFor(String profileKey, String category, BlockPos pos) {
        RegionManager rm = RegionManager.get();
        return switch (category) {
            case "hostile" -> rm.hostileMobsAggressiveness(profileKey, pos);
            case "passive" -> rm.passiveMobsAggressiveness(profileKey, pos);
            case "player" -> rm.playersAggressiveness(profileKey, pos);
            default -> Aggressiveness.OFF;
        };
    }
}
