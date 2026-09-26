package com.ardor.game;

import com.ardor.client.StatusIndicator;
import com.ardor.region.RegionManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        begin(e -> e.getType() == type, null, Kind.ENTITY_TYPE, type);
    }

    /** Area Selection's "Kill Hostile Mobs" -- every alive hostile (Monster) within the given area. */
    public static void startHostilesInArea(AABB area) {
        begin(e -> e instanceof Monster, area, Kind.HOSTILES_IN_AREA, null);
    }

    /** Arbitrary-filter entry point for ScriptEngine's killAll(regex, ...) binding -- same loop as start(EntityType), just not restricted to one exact type. area null = radius-around-player. Not resumable (see resume()'s doc): an arbitrary Lua predicate can't be serialized, so re-running the script is how this one gets "resumed." */
    public static void startMatching(Predicate<Entity> entityFilter, AABB area) {
        begin(entityFilter, area, null, null);
    }

    private static void begin(Predicate<Entity> newFilter, AABB newFixedArea, Kind kind, EntityType<?> entityType) {
        filter = newFilter;
        fixedArea = newFixedArea;
        active = true;
        if (kind != null) persist(kind, entityType, newFixedArea); else clearPersisted();
        ensureTicker();
    }

    public static void stop() {
        active = false;
        filter = null;
        fixedArea = null;
        // Resume file deliberately left in place -- an explicit stop (including panic stop) is
        // still resumable, same as BreakAreaController.cancel(). Only a natural "nothing left to
        // kill" finish or an explicit discardResumable() clears it.
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
            active = false; // nothing left in range -- genuinely done, not just interrupted
            clearPersisted();
            return;
        }
        GameActionController.attackEntityUntilDead(nearest);
    }

    // ------------------------------------------------------------------ resume / persistence

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private enum Kind { ENTITY_TYPE, HOSTILES_IN_AREA }

    private record AreaBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        static AreaBox of(AABB box) { return new AreaBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ); }
        AABB toAABB() { return new AABB(minX, minY, minZ, maxX, maxY, maxZ); }
    }

    private record ResumeState(String profileKey, Kind kind, String entityTypeId, AreaBox area) {}

    private static Path resumePath() {
        return FabricLoader.getInstance().getConfigDir().resolve("ardor-killall-resume.json");
    }

    private static void persist(Kind kind, EntityType<?> entityType, AABB area) {
        String entityTypeId = entityType != null ? BuiltInRegistries.ENTITY_TYPE.getKey(entityType).toString() : null;
        ResumeState state = new ResumeState(RegionManager.currentProfileKey(), kind, entityTypeId,
                area != null ? AreaBox.of(area) : null);
        try {
            Files.writeString(resumePath(), GSON.toJson(state));
        } catch (IOException e) {
            System.err.println("[ardor] kill all: failed to persist resume state: " + e);
        }
    }

    private static void clearPersisted() {
        try {
            Files.deleteIfExists(resumePath());
        } catch (IOException e) {
            System.err.println("[ardor] kill all: failed to clear resume state: " + e);
        }
    }

    private static ResumeState loadPersisted() {
        Path p = resumePath();
        if (!Files.exists(p)) return null;
        try {
            return GSON.fromJson(Files.readString(p), ResumeState.class);
        } catch (IOException e) {
            System.err.println("[ardor] kill all: failed to load resume state: " + e);
            return null;
        }
    }

    /** For the "found interrupted work" world-join check and the Resume Interrupted Work screen. */
    public static boolean hasResumable() {
        if (active) return false;
        ResumeState state = loadPersisted();
        return state != null && state.profileKey().equals(RegionManager.currentProfileKey());
    }

    public static String resumableSummary() {
        ResumeState state = loadPersisted();
        if (state == null) return null;
        return state.kind() == Kind.ENTITY_TYPE
                ? "Kill All: " + shortEntityName(state.entityTypeId())
                : "Kill Hostile Mobs in the selected area";
    }

    private static String shortEntityName(String entityTypeId) {
        int colon = entityTypeId == null ? -1 : entityTypeId.indexOf(':');
        return colon < 0 ? String.valueOf(entityTypeId) : entityTypeId.substring(colon + 1);
    }

    public static void discardResumable() {
        clearPersisted();
    }

    /** Re-derives filter/fixedArea from the persisted state and restarts the scan loop. See startMatching's doc for why a CUSTOM (script-driven) run never reaches here -- it's never persisted in the first place. */
    public static void resume() {
        if (active) return;
        ResumeState state = loadPersisted();
        if (state == null || !state.profileKey().equals(RegionManager.currentProfileKey())) return;
        if (state.kind() == Kind.ENTITY_TYPE) {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(state.entityTypeId()));
            if (type == null) return;
            filter = e -> e.getType() == type;
            fixedArea = null;
        } else {
            filter = e -> e instanceof Monster;
            fixedArea = state.area().toAABB();
        }
        active = true;
        StatusIndicator.show("Resuming: " + resumableSummary());
        ensureTicker();
    }
}
