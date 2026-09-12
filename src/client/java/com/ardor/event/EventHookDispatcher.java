package com.ardor.event;

import com.google.gson.JsonObject;
import com.ardor.bridge.BridgeServer;
import com.ardor.client.StatusIndicator;
import com.ardor.ir.AsciiActionCodec;
import com.ardor.planner.PlannedTask;
import com.ardor.planner.TaskPlanner;
import com.ardor.planner.TaskRunner;
import com.ardor.region.RegionManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Wires DomainEvents (its synthesized custom events -- see that class's own doc for the current
 * list) plus a real subset of raw Fabric events (FabricEventCatalog.WIRED_FABRIC_EVENTS) to region-scoped
 * task lookup: when a wired event fires, resolve the player's current
 * profile+region (RegionManager, "lowest sub-region the player is in"), look
 * up that event id's bound task text (walking up the region's parent chain,
 * then the global profile, per RegionManager.resolveEventTask), and if one
 * is bound, plan it and push it to the front of the shared TaskRunner,
 * interrupting whatever's currently running.
 *
 * Unwired FabricEventCatalog entries are real, present in the dropdown, and
 * do nothing yet -- see that class's javadoc and TODO.md for what's left.
 */
public final class EventHookDispatcher {

    private EventHookDispatcher() {}

    public static void register() {
        DomainEvents.register(EventHookDispatcher::fireDomainEvent);

        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> fire("Fabric.ClientLifecycleEvents.CLIENT_STARTED"));
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> fire("Fabric.ClientLifecycleEvents.CLIENT_STOPPING"));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> fire("Fabric.ClientPlayConnectionEvents.JOIN"));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> fire("Fabric.ClientPlayConnectionEvents.DISCONNECT"));
        ClientReceiveMessageEvents.CHAT.register((message, signed, profile, type, receptionTimestamp) -> {
            String text = message.getString();
            JsonObject payload = new JsonObject();
            payload.addProperty("message", text);
            fire("Fabric.ClientReceiveMessageEvents.CHAT", payload);
            fireChatPhrase(text);
        });
        ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> {
            fire("Fabric.ClientPlayerBlockBreakEvents.AFTER");
            // world.blockChanged (see the approved plan): only covers blocks broken by this bot
            // for now, not every block change nearby (redstone, other players, falling blocks) --
            // a general "any block changed" detector needs packet-level interception or chunk-
            // section diffing, out of scope until Phase 2's terrain cache actually needs it.
            JsonObject payload = new JsonObject();
            payload.addProperty("x", pos.getX());
            payload.addProperty("y", pos.getY());
            payload.addProperty("z", pos.getZ());
            payload.addProperty("newState", level.getBlockState(pos).toString());
            BridgeServer.pushEvent("world.blockChanged", payload);
        });
        EntitySleepEvents.START_SLEEPING.register((entity, pos) -> fire("Fabric.EntitySleepEvents.START_SLEEPING"));
        EntitySleepEvents.STOP_SLEEPING.register((entity, pos) -> fire("Fabric.EntitySleepEvents.STOP_SLEEPING"));
    }

    private static void fire(String eventId) {
        fire(eventId, null);
    }

    /**
     * Every fabric.* event id this mod recognizes funnels through here -- a single relay point to
     * the bridge for the companion app, rather than a second parallel set of Fabric event
     * registrations duplicating this one's. Confirmed live 2026-09-03: the CHAT registration used
     * to call both this AND a separate BridgeServer.pushEvent with the message payload for the
     * same eventId, so the companion received the same chat message as two event frames -- one
     * bare, one with text. This overload lets a caller attach a payload to the SAME push instead
     * of a second one. Resolves the bound task against RegionManager.playerPos() (wherever the
     * player currently is) -- correct for every raw Fabric event, which has no notion of "resolve
     * this at a DIFFERENT position" the way OnRegionExit needs (see fireDomainEvent).
     */
    private static void fire(String eventId, JsonObject payload) {
        BridgeServer.pushEvent(eventId, payload);
        try {
            fireInner(eventId, RegionManager.playerPos());
        } catch (RuntimeException e) {
            System.err.println("[ardor] event hook failed for " + eventId + ": " + e);
        }
    }

    /**
     * DomainEvents' own listener registration (DomainEvents.register(EventHookDispatcher::
     * fireDomainEvent)) -- unlike the raw-Fabric-event fire() above, DomainEvents hands back the
     * exact position each event should resolve against (see its own class doc for why
     * OnRegionExit specifically needs LAST tick's position, not the player's current one).
     */
    private static void fireDomainEvent(String eventId, BlockPos at) {
        BridgeServer.pushEvent(eventId, null);
        try {
            fireInner(eventId, at);
        } catch (RuntimeException e) {
            System.err.println("[ardor] event hook failed for " + eventId + ": " + e);
        }
    }

    /** "Add an event to react to a message in chat" -- checks the current region chain for any bound chat-phrase (RegionManager.CHAT_PHRASE_PREFIX, edited via EventConfigScreen's phrase box), case-insensitive substring match against the raw chat text. */
    private static void fireChatPhrase(String message) {
        try {
            String profileKey = RegionManager.currentProfileKey();
            BlockPos pos = RegionManager.playerPos();
            String taskText = RegionManager.get().resolveChatPhraseTask(profileKey, pos, message);
            if (taskText == null || taskText.isBlank()) return;

            StatusIndicator.show("Chat phrase matched -> " + taskText);
            TaskPlanner.plan(taskText)
                    .thenAccept(tasks -> Minecraft.getInstance().execute(() -> runUrgent("OnChatPhrase", tasks)))
                    .exceptionally(err -> {
                        System.err.println("[ardor] chat-phrase plan failed for '" + taskText + "': " + err.getMessage());
                        return null;
                    });
        } catch (RuntimeException e) {
            System.err.println("[ardor] chat-phrase check failed: " + e);
        }
    }

    private static void fireInner(String eventId, BlockPos pos) {
        String profileKey = RegionManager.currentProfileKey();
        String taskText = RegionManager.get().resolveEventTask(profileKey, pos, eventId);
        if (taskText == null || taskText.isBlank()) return;

        StatusIndicator.show("Event " + eventId + " -> " + taskText);

        // A bound task that's already valid ascii-grammar (e.g. "atk @e[category=hostile,...]
        // until:dead") skips the LLM entirely: there's no natural-language ambiguity to resolve, and
        // a reflex binding like on-damage defense can't afford a multi-second planning round trip
        // before it reacts. Natural-language goals (AsciiActionCodec.decode throws on those) fall
        // through to the existing planner path unchanged.
        try {
            AsciiActionCodec.decode(taskText);
            Minecraft.getInstance().execute(() -> runUrgent(eventId, List.of(new PlannedTask(eventId, List.of(taskText)))));
            return;
        } catch (RuntimeException notAscii) {
            // fall through to the planner below
        }

        TaskPlanner.plan(taskText)
                .thenAccept(tasks -> Minecraft.getInstance().execute(() -> runUrgent(eventId, tasks)))
                .exceptionally(err -> {
                    System.err.println("[ardor] event-triggered plan failed for '" + taskText + "': " + err.getMessage());
                    StatusIndicator.show("Event task failed to plan: " + err.getMessage());
                    return null;
                });
    }

    private static void runUrgent(String eventId, List<PlannedTask> tasks) {
        TaskRunner.shared().interrupt(tasks, new TaskRunner.Listener() {
            @Override public void onTaskStarted(int taskIndex, PlannedTask task) {
                StatusIndicator.show("[" + eventId + "] " + task.description());
            }
            @Override public void onCommandStarted(int taskIndex, int commandIndex, String command) {}
            @Override public void onCommandFailed(int taskIndex, int commandIndex, String command, String error) {
                StatusIndicator.show("[" + eventId + "] command failed: " + command + " -- " + error);
            }
            @Override public void onTaskFinished(int taskIndex) {}
            @Override public void onPlanFinished() {}
        });
    }
}
