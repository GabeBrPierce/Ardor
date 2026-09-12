package com.ardor.event;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every event id selectable on EventConfigScreen's dropdown. Two kinds:
 *
 * DOMAIN events (see DomainEvents.java): higher-level conditions this mod
 * detects itself by polling client state each tick, not raw Fabric API
 * callbacks -- OnIdle, OnClientTakesDamage, OnFollowedEntityTakesDamage,
 * OnPlayerDetected, OnRegionEnter, OnRegionExit. All are fully wired (EventHookDispatcher).
 *
 * FABRIC events: real net.fabricmc.fabric.api.event.Event fields, enumerated
 * by javap disassembly of every client-relevant class in fabric-api
 * 0.148.0+26.1.2 (this project's declared version) this session -- not
 * guessed or padded to hit a round number. WIRED_FABRIC_EVENTS (see below)
 * lists which of these EventHookDispatcher actually registers a live
 * listener for; the rest are real, present, selectable in the dropdown, but
 * picking one that isn't in WIRED_FABRIC_EVENTS won't currently fire
 * anything -- see TODO.md for the wiring-remaining list. Server-side events
 * (ServerPlayerEvents, ServerLivingEntityEvents, ServerEntityCombatEvents,
 * CommonLifecycleEvents) are included because the integrated server for a
 * singleplayer world runs in this same process and these DO fire there, but
 * they need a server-context registration point this mod doesn't have yet
 * (this mod's fabric.mod.json is "environment": "client" only) -- also
 * unwired for now, listed for completeness/future work.
 *
 * The per-Screen events (ScreenEvents/ScreenKeyboardEvents/ScreenMouseEvents)
 * are architecturally different -- each is obtained via a static factory
 * method taking a specific Screen instance (e.g. ScreenEvents.afterInit(screen)),
 * not a single global Event field -- so "register once, fires for any screen"
 * doesn't apply the same way. Listed for completeness; not wireable as a
 * simple global hook without deciding which screen(s) to attach to.
 */
public final class FabricEventCatalog {

    public static final List<String> DOMAIN_EVENTS = List.of(
            "OnIdle",
            "OnClientTakesDamage",
            "OnFollowedEntityTakesDamage",
            "OnPlayerDetected",
            "OnRegionEnter",
            "OnRegionExit"
    );

    public static final List<String> FABRIC_EVENTS = List.of(
            // ClientBlockEntityEvents
            "Fabric.ClientBlockEntityEvents.BLOCK_ENTITY_LOAD",
            "Fabric.ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD",
            // ClientChunkEvents
            "Fabric.ClientChunkEvents.CHUNK_LOAD",
            "Fabric.ClientChunkEvents.CHUNK_UNLOAD",
            // ClientEntityEvents
            "Fabric.ClientEntityEvents.ENTITY_LOAD",
            "Fabric.ClientEntityEvents.ENTITY_UNLOAD",
            // ClientLevelEvents
            "Fabric.ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE",
            // ClientLifecycleEvents
            "Fabric.ClientLifecycleEvents.CLIENT_STARTED",
            "Fabric.ClientLifecycleEvents.CLIENT_STOPPING",
            // ClientTickEvents
            "Fabric.ClientTickEvents.START_CLIENT_TICK",
            "Fabric.ClientTickEvents.END_CLIENT_TICK",
            "Fabric.ClientTickEvents.START_LEVEL_TICK",
            "Fabric.ClientTickEvents.END_LEVEL_TICK",
            // ClientReceiveMessageEvents
            "Fabric.ClientReceiveMessageEvents.ALLOW_CHAT",
            "Fabric.ClientReceiveMessageEvents.ALLOW_GAME",
            "Fabric.ClientReceiveMessageEvents.MODIFY_GAME",
            "Fabric.ClientReceiveMessageEvents.CHAT",
            "Fabric.ClientReceiveMessageEvents.GAME",
            "Fabric.ClientReceiveMessageEvents.CHAT_CANCELED",
            "Fabric.ClientReceiveMessageEvents.GAME_CANCELED",
            // ClientSendMessageEvents
            "Fabric.ClientSendMessageEvents.ALLOW_CHAT",
            "Fabric.ClientSendMessageEvents.ALLOW_COMMAND",
            "Fabric.ClientSendMessageEvents.MODIFY_CHAT",
            "Fabric.ClientSendMessageEvents.MODIFY_COMMAND",
            "Fabric.ClientSendMessageEvents.CHAT",
            "Fabric.ClientSendMessageEvents.COMMAND",
            "Fabric.ClientSendMessageEvents.CHAT_CANCELED",
            "Fabric.ClientSendMessageEvents.COMMAND_CANCELED",
            // ClientPlayConnectionEvents
            "Fabric.ClientPlayConnectionEvents.INIT",
            "Fabric.ClientPlayConnectionEvents.JOIN",
            "Fabric.ClientPlayConnectionEvents.DISCONNECT",
            // ParticleRenderEvents
            "Fabric.ParticleRenderEvents.ALLOW_TERRAIN_PARTICLE_TINT",
            // ClientRecipeSynchronizedEvent
            "Fabric.ClientRecipeSynchronizedEvent.EVENT",
            // LivingEntityFeatureRenderEvents
            "Fabric.LivingEntityFeatureRenderEvents.ALLOW_CAPE_RENDER",
            // LevelRenderEvents
            "Fabric.LevelRenderEvents.AFTER_BLOCK_OUTLINE_EXTRACTION",
            "Fabric.LevelRenderEvents.END_EXTRACTION",
            "Fabric.LevelRenderEvents.START_MAIN",
            "Fabric.LevelRenderEvents.AFTER_OPAQUE_TERRAIN",
            "Fabric.LevelRenderEvents.COLLECT_SUBMITS",
            "Fabric.LevelRenderEvents.AFTER_SOLID_FEATURES",
            "Fabric.LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES",
            "Fabric.LevelRenderEvents.BEFORE_BLOCK_OUTLINE",
            "Fabric.LevelRenderEvents.BEFORE_GIZMOS",
            "Fabric.LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN",
            "Fabric.LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN",
            "Fabric.LevelRenderEvents.END_MAIN",
            // ScreenEvents (2 global static fields; the rest are per-screen factory methods, see class javadoc)
            "Fabric.ScreenEvents.BEFORE_INIT",
            "Fabric.ScreenEvents.AFTER_INIT",
            "Fabric.ScreenEvents.remove(screen)",
            "Fabric.ScreenEvents.beforeExtract(screen)",
            "Fabric.ScreenEvents.afterBackground(screen)",
            "Fabric.ScreenEvents.afterExtract(screen)",
            "Fabric.ScreenEvents.beforeTick(screen)",
            "Fabric.ScreenEvents.afterTick(screen)",
            // ScreenKeyboardEvents (all per-screen factory methods)
            "Fabric.ScreenKeyboardEvents.allowKeyPress(screen)",
            "Fabric.ScreenKeyboardEvents.beforeKeyPress(screen)",
            "Fabric.ScreenKeyboardEvents.afterKeyPress(screen)",
            "Fabric.ScreenKeyboardEvents.allowKeyRelease(screen)",
            "Fabric.ScreenKeyboardEvents.beforeKeyRelease(screen)",
            "Fabric.ScreenKeyboardEvents.afterKeyRelease(screen)",
            // ScreenMouseEvents (all per-screen factory methods)
            "Fabric.ScreenMouseEvents.allowMouseClick(screen)",
            "Fabric.ScreenMouseEvents.beforeMouseClick(screen)",
            "Fabric.ScreenMouseEvents.afterMouseClick(screen)",
            "Fabric.ScreenMouseEvents.allowMouseRelease(screen)",
            "Fabric.ScreenMouseEvents.beforeMouseRelease(screen)",
            "Fabric.ScreenMouseEvents.afterMouseRelease(screen)",
            "Fabric.ScreenMouseEvents.allowMouseDrag(screen)",
            "Fabric.ScreenMouseEvents.beforeMouseDrag(screen)",
            "Fabric.ScreenMouseEvents.afterMouseDrag(screen)",
            "Fabric.ScreenMouseEvents.allowMouseScroll(screen)",
            "Fabric.ScreenMouseEvents.beforeMouseScroll(screen)",
            "Fabric.ScreenMouseEvents.afterMouseScroll(screen)",
            // ClientHotbarScrollEvents
            "Fabric.ClientHotbarScrollEvents.ALLOW",
            "Fabric.ClientHotbarScrollEvents.BEFORE",
            "Fabric.ClientHotbarScrollEvents.AFTER",
            // ClientPlayerBlockBreakEvents
            "Fabric.ClientPlayerBlockBreakEvents.AFTER",
            // EntityElytraEvents
            "Fabric.EntityElytraEvents.ALLOW",
            "Fabric.EntityElytraEvents.CUSTOM",
            // EntitySleepEvents
            "Fabric.EntitySleepEvents.ALLOW_SLEEPING",
            "Fabric.EntitySleepEvents.START_SLEEPING",
            "Fabric.EntitySleepEvents.STOP_SLEEPING",
            "Fabric.EntitySleepEvents.ALLOW_BED",
            "Fabric.EntitySleepEvents.ALLOW_NEARBY_MONSTERS",
            "Fabric.EntitySleepEvents.ALLOW_RESETTING_TIME",
            "Fabric.EntitySleepEvents.MODIFY_SLEEPING_DIRECTION",
            "Fabric.EntitySleepEvents.ALLOW_SETTING_SPAWN",
            "Fabric.EntitySleepEvents.SET_BED_OCCUPATION_STATE",
            "Fabric.EntitySleepEvents.MODIFY_WAKE_UP_POSITION",
            // Server-side (fire via the integrated server in singleplayer; not wired, see class javadoc)
            "Fabric.ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY",
            "Fabric.ServerLivingEntityEvents.ALLOW_DAMAGE",
            "Fabric.ServerLivingEntityEvents.AFTER_DAMAGE",
            "Fabric.ServerLivingEntityEvents.ALLOW_DEATH",
            "Fabric.ServerLivingEntityEvents.AFTER_DEATH",
            "Fabric.ServerLivingEntityEvents.MOB_CONVERSION",
            "Fabric.ServerPlayerEvents.COPY_FROM",
            "Fabric.ServerPlayerEvents.AFTER_RESPAWN",
            "Fabric.ServerPlayerEvents.JOIN",
            "Fabric.ServerPlayerEvents.LEAVE",
            "Fabric.ServerPlayerEvents.ALLOW_DEATH",
            "Fabric.CommonLifecycleEvents.TAGS_LOADED"
    );

    /** Which FABRIC_EVENTS entries EventHookDispatcher actually registers a live listener for. */
    public static final Set<String> WIRED_FABRIC_EVENTS = new LinkedHashSet<>(List.of(
            "Fabric.ClientLifecycleEvents.CLIENT_STARTED",
            "Fabric.ClientLifecycleEvents.CLIENT_STOPPING",
            "Fabric.ClientPlayConnectionEvents.JOIN",
            "Fabric.ClientPlayConnectionEvents.DISCONNECT",
            "Fabric.ClientReceiveMessageEvents.CHAT",
            "Fabric.ClientPlayerBlockBreakEvents.AFTER",
            "Fabric.EntitySleepEvents.START_SLEEPING",
            "Fabric.EntitySleepEvents.STOP_SLEEPING"
    ));

    public static List<String> allEventIds() {
        List<String> all = new java.util.ArrayList<>(DOMAIN_EVENTS);
        all.addAll(FABRIC_EVENTS);
        return all;
    }

    public static boolean isWired(String eventId) {
        return DOMAIN_EVENTS.contains(eventId) || WIRED_FABRIC_EVENTS.contains(eventId);
    }

    private FabricEventCatalog() {}
}
