package com.ardor.event;

import com.ardor.game.GameActionController;
import com.ardor.game.PathfindingController;
import com.ardor.region.Region;
import com.ardor.region.RegionManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The four required higher-level events, detected by polling client state
 * every tick -- none of these are raw Fabric API callbacks (Fabric has no
 * "a player walked into render distance" or "the bot's current goal has sat
 * idle" event), so this mod synthesizes them itself:
 *
 *   OnIdle                      -- no goto/mine/attack-until-dead busy AND no
 *                                   TaskRunner plan active, sustained for
 *                                   IDLE_TICKS_THRESHOLD (30s) -- fires once
 *                                   per idle period, not every tick while idle
 *   OnClientTakesDamage          -- local player's health drops between ticks
 *   OnFollowedEntityTakesDamage  -- the current `follow` target's health
 *                                   drops between ticks (no-op if not
 *                                   following, or the target isn't a
 *                                   LivingEntity)
 *   OnPlayerDetected             -- another player enters DETECT_RADIUS that
 *                                   wasn't within it last tick
 *   OnRegionEnter / OnRegionExit -- the player's resolved region (RegionManager.resolveRegion,
 *                                   "lowest sub-region the player is in") differs from last tick's.
 *                                   Fired against the position that ACTUALLY belonged to each side
 *                                   of the transition (OnRegionExit resolved at last tick's
 *                                   position -- still inside the region being left -- OnRegionEnter
 *                                   at the current one), not both against "wherever the player is
 *                                   right now" the way every other event here works, since by the
 *                                   time a transition is detected the player is already standing in
 *                                   the new region. That's why Listener carries a BlockPos now.
 *
 * Health-delta detection (not a real damage-event hook) means: doesn't
 * distinguish damage from healing-then-damage landing on the same tick as a
 * coincidental regen tick, and misses damage+full-regen happening within one
 * tick (health same before/after). Good enough for "something hit me/them,"
 * not a combat-log-accurate signal.
 */
public final class DomainEvents {

    public interface Listener {
        void onDomainEvent(String eventId, BlockPos at);
    }

    private static final int IDLE_TICKS_THRESHOLD = 20 * 30; // 30s at 20 tps
    private static final double DETECT_RADIUS = 32.0;
    private static final double DETECT_RADIUS_SQ = DETECT_RADIUS * DETECT_RADIUS;

    private int idleTicks;
    private boolean idleFired;
    private float lastHealth = -1;
    private float lastFollowedHealth = -1;
    private Set<UUID> knownNearbyPlayers = new HashSet<>();
    private String lastRegionName;
    private BlockPos lastPos;

    public static void register(Listener listener) {
        DomainEvents instance = new DomainEvents();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                instance.tick(client, listener);
            } catch (RuntimeException e) {
                System.err.println("[ardor] DomainEvents tick failed: " + e);
            }
        });
    }

    private void tick(Minecraft client, Listener listener) {
        LocalPlayer player = client.player;
        if (player == null) {
            idleTicks = 0;
            idleFired = false;
            lastHealth = -1;
            lastFollowedHealth = -1;
            knownNearbyPlayers = new HashSet<>();
            lastRegionName = null;
            lastPos = null;
            return;
        }

        checkIdle(player, listener);
        checkClientDamage(player, listener);
        checkFollowedDamage(player, listener);
        checkPlayerDetected(client, player, listener);
        checkRegionTransition(player, listener);
        ScriptEventRegistry.tick();
    }

    private void checkIdle(LocalPlayer player, Listener listener) {
        boolean busy = PathfindingController.isBusy() || GameActionController.isBusy();
        if (busy) {
            idleTicks = 0;
            idleFired = false;
            return;
        }
        if (idleFired) return;
        if (++idleTicks >= IDLE_TICKS_THRESHOLD) {
            idleFired = true;
            listener.onDomainEvent("OnIdle", player.blockPosition());
        }
    }

    private void checkClientDamage(LocalPlayer player, Listener listener) {
        float health = player.getHealth();
        if (lastHealth >= 0 && health < lastHealth) {
            listener.onDomainEvent("OnClientTakesDamage", player.blockPosition());
        }
        lastHealth = health;
    }

    private void checkFollowedDamage(LocalPlayer player, Listener listener) {
        Entity followed = PathfindingController.getFollowTarget();
        if (!(followed instanceof LivingEntity living) || !followed.isAlive()) {
            lastFollowedHealth = -1;
            return;
        }
        float health = living.getHealth();
        if (lastFollowedHealth >= 0 && health < lastFollowedHealth) {
            listener.onDomainEvent("OnFollowedEntityTakesDamage", player.blockPosition());
        }
        lastFollowedHealth = health;
    }

    /**
     * "In regions we should add onEnter and onExit events." Fires OnRegionExit resolved at LAST
     * tick's position (still inside the region being left) and OnRegionEnter at the current one --
     * see this class's own doc for why both can't just use "wherever the player is right now" the
     * way every other event here does. `lastPos == null` on the very first tick (or after a
     * player/level reload) means there's nothing to compare against yet -- no events fire, just
     * primes the tracking state.
     */
    private void checkRegionTransition(LocalPlayer player, Listener listener) {
        String profileKey = RegionManager.currentProfileKey();
        BlockPos pos = player.blockPosition();
        Region current = RegionManager.get().resolveRegion(profileKey, pos);
        String currentName = current != null ? current.name : null;

        if (lastPos != null && !java.util.Objects.equals(lastRegionName, currentName)) {
            listener.onDomainEvent("OnRegionExit", lastPos);
            listener.onDomainEvent("OnRegionEnter", pos);
        }
        lastRegionName = currentName;
        lastPos = pos;
    }

    private void checkPlayerDetected(Minecraft client, LocalPlayer player, Listener listener) {
        ClientLevel level = client.level;
        if (level == null) return;
        Set<UUID> nearby = new HashSet<>();
        boolean newDetection = false;
        for (AbstractClientPlayer p : level.players()) {
            if (p == player) continue;
            if (p.distanceToSqr(player) > DETECT_RADIUS_SQ) continue;
            nearby.add(p.getUUID());
            if (!knownNearbyPlayers.contains(p.getUUID())) newDetection = true;
        }
        knownNearbyPlayers = nearby;
        if (newDetection) listener.onDomainEvent("OnPlayerDetected", player.blockPosition());
    }
}
