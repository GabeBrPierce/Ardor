package com.ardor.bridge;

import com.google.gson.JsonObject;
import com.ardor.game.GrindModeController;
import com.ardor.planner.TaskOrchestrator;
import com.ardor.planner.TaskRunner;
import com.ardor.region.RegionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Bridge message dispatch (see the approved plan). Runs on the Minecraft
 * main thread already; BridgeServer.FrameHandler marshals every call here
 * via Minecraft.getInstance().execute() before this is reached, the same
 * pattern every other async entry point in this mod (VoicePipeline,
 * TaskRunner, AgentControlChannel) already uses.
 *
 * Phase 1: the full low-level command/query surface the plan describes,
 * except macros-as-a-concept and region wireframe rendering (both Phase 4).
 * Held/ticked primitives (input.set, look.at, block.breakStart/Stop) each
 * have their own small controller class; everything else here is a single
 * synchronous call.
 */
final class BridgeDispatcher {

    private BridgeDispatcher() {}

    static JsonObject dispatch(JsonObject msg) {
        String type = msg.get("type").getAsString();
        return switch (type) {
            case "query" -> handleQuery(msg);
            case "command" -> { handleCommand(msg); yield null; }
            default -> throw new IllegalArgumentException("unknown message type: " + type);
        };
    }

    private static JsonObject handleQuery(JsonObject msg) {
        String what = msg.get("what").getAsString();
        JsonObject result = switch (what) {
            case "player.state" -> playerState();
            case "inventory.contents" -> BridgeQueries.inventoryContents();
            case "world.time" -> BridgeQueries.worldTime();
            case "world.blockAt" -> BridgeQueries.worldBlockAt(msg);
            case "world.blockRegion" -> BridgeQueries.worldBlockRegion(msg);
            case "world.nearestBlocks" -> BridgeQueries.worldNearestBlocks(msg);
            case "world.search" -> BridgeQueries.worldSearch(msg);
            case "world.nearbyEntities" -> BridgeQueries.worldNearbyEntities(msg);
            case "world.droppedItems" -> BridgeQueries.droppedItems(msg);
            case "world.surroundings" -> BridgeQueries.worldSurroundings(msg);
            case "world.chunkHeightmap" -> BridgeQueries.worldChunkHeightmap(msg);
            case "world.nearbyContainers" -> BridgeQueries.worldNearbyContainers(msg);
            case "session.info" -> BridgeQueries.sessionInfo();
            case "system.windowState" -> BridgeQueries.windowState();
            case "system.listWorlds" -> BridgeQueries.systemListWorlds();
            case "system.difficulty" -> BridgeQueries.systemDifficulty();
            case "nav.status" -> BaritoneNav.status();
            case "ui.list" -> BridgeUIController.listOptions();
            case "region.list" -> BridgeQueries.regionList();
            case "runner.status" -> TaskRunner.shared().status();
            case "container.sources.list" -> BridgeContainerController.sourcesList();
            case "container.cache.list" -> BridgeContainerController.cacheList(msg);
            case "container.fetch.status" -> BridgeContainerController.fetchStatus();
            default -> throw new IllegalArgumentException("unknown query: " + what);
        };
        // Correlation field is "reqId", not "id" -- deliberately distinct from any domain field a
        // query's own args might legitimately be named (world.nearestBlocks takes an "id" for the
        // block reference; a query response echoing back "id":"minecraft:stone" instead of the real
        // request id would silently strand the caller's future forever). Confirmed live: this exact
        // collision caused world.nearestBlocks to time out on the companion side before this rename.
        JsonObject response = new JsonObject();
        response.addProperty("type", "queryResult");
        if (msg.has("reqId")) response.addProperty("reqId", msg.get("reqId").getAsString());
        response.add("result", result);
        return response;
    }

    private static void handleCommand(JsonObject msg) {
        String command = msg.get("command").getAsString();
        switch (command) {
            case "input.set" -> BridgeInputController.setInput(
                    bool(msg, "forward"), bool(msg, "back"), bool(msg, "left"), bool(msg, "right"),
                    bool(msg, "jump"), bool(msg, "sneak"), bool(msg, "sprint"));
            case "look.set" -> lookSet(msg);
            case "look.at" -> BridgeLookController.setTarget(
                    msg.get("x").getAsDouble(), msg.get("y").getAsDouble(), msg.get("z").getAsDouble(),
                    msg.has("maxDegPerTick") ? msg.get("maxDegPerTick").getAsFloat() : 15f);
            case "look.clear" -> BridgeLookController.clear();
            case "block.breakStart" -> BridgeBreakController.start(readPos(msg), optionalFace(msg));
            case "block.breakStop" -> BridgeBreakController.stop();
            case "block.place" -> BridgeActionHandlers.blockPlace(msg);
            case "entity.attack" -> BridgeActionHandlers.entityAttack(msg);
            case "entity.interact" -> BridgeActionHandlers.entityInteract(msg);
            case "item.use" -> BridgeActionHandlers.itemUse(msg);
            case "inventory.selectSlot" -> BridgeActionHandlers.inventorySelectSlot(msg);
            case "inventory.moveItem" -> BridgeActionHandlers.inventoryMoveItem(msg);
            case "inventory.dropSlot" -> BridgeActionHandlers.inventoryDropSlot(msg);
            case "chat.send" -> BridgeActionHandlers.chatSend(msg);
            case "chat.whisper" -> BridgeActionHandlers.chatWhisper(msg);
            case "command.sendRaw" -> BridgeActionHandlers.commandSendRaw(msg);
            case "system.restartSoft" -> BridgeActionHandlers.systemRestartSoft();
            case "system.relaunch" -> BridgeActionHandlers.systemRelaunch();
            case "system.shutdown" -> BridgeActionHandlers.systemShutdown();
            case "system.resumeWorld" -> WorldResumer.resumeWorld(msg.has("name") ? msg.get("name").getAsString() : null);
            case "system.respawn" -> WorldResumer.respawn();
            case "system.setDifficulty" -> WorldResumer.setDifficulty(msg.get("difficulty").getAsString());
            case "ui.select" -> BridgeUIController.select(msg);
            case "nav.goto" -> BaritoneNav.goTo(msg);
            case "nav.stop" -> BaritoneNav.stop();
            case "mode.grindMobs" -> setGrindMode(bool(msg, "active"));
            case "region.set" -> regionSet(msg);
            case "region.delete" -> RegionManager.get().deleteRegion(
                    RegionManager.currentProfileKey(), msg.get("name").getAsString());
            // Pauses/resumes whichever plan is currently driving TaskRunner.shared(), regardless
            // of whether it got there via TaskPlannerScreen's Run/Auto-Run or an event-driven
            // interrupt -- same real halt-in-place TaskRunner.pause() the mod's own UI button uses,
            // just reachable remotely so the companion web dashboard can control it too.
            case "runner.pause" -> pauseRunner();
            case "runner.resume" -> resumeRunner();
            case "container.source.add" -> BridgeContainerController.sourceAdd(msg);
            case "container.source.edit" -> BridgeContainerController.sourceEdit(msg);
            case "container.source.remove" -> BridgeContainerController.sourceRemove(msg);
            case "container.source.setEnabled" -> BridgeContainerController.sourceSetEnabled(msg);
            case "container.cache.refresh" -> BridgeContainerController.cacheRefresh(msg);
            case "container.fetch" -> BridgeContainerController.fetch(msg);
            default -> throw new IllegalArgumentException("unknown command: " + command);
        }
    }

    /** {name, min:{x,y,z}, max:{x,y,z}} -- the remote equivalent of `//ardor set region <name> ...`, which command.sendRaw can't reach since that's a client-side Fabric command, not a server one. */
    private static void regionSet(JsonObject msg) {
        String name = msg.get("name").getAsString();
        BlockPos min = readPos(msg.getAsJsonObject("min"));
        BlockPos max = readPos(msg.getAsJsonObject("max"));
        RegionManager.get().setRegion(RegionManager.currentProfileKey(), name, min, max);
    }

    private static void pauseRunner() {
        TaskRunner.shared().pause();
        if (TaskOrchestrator.isActive()) TaskOrchestrator.pause();
    }

    private static void resumeRunner() {
        TaskRunner.shared().resume();
        if (TaskOrchestrator.isActive()) TaskOrchestrator.resume();
    }

    private static void setGrindMode(boolean active) {
        if (active) GrindModeController.start();
        else GrindModeController.stop();
    }

    private static void lookSet(JsonObject msg) {
        BridgeLookController.clear();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) throw new IllegalStateException("no client player loaded");
        float yaw = msg.get("yaw").getAsFloat();
        float pitch = msg.get("pitch").getAsFloat();
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.setYHeadRot(yaw);
    }

    private static BlockPos readPos(JsonObject msg) {
        return new BlockPos(msg.get("x").getAsInt(), msg.get("y").getAsInt(), msg.get("z").getAsInt());
    }

    private static Direction optionalFace(JsonObject msg) {
        return msg.has("face") ? Direction.valueOf(msg.get("face").getAsString().toUpperCase()) : null;
    }

    private static boolean bool(JsonObject msg, String field) {
        return msg.has(field) && msg.get(field).getAsBoolean();
    }

    private static JsonObject playerState() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) throw new IllegalStateException("no client player loaded");
        JsonObject result = new JsonObject();
        result.addProperty("x", player.getX());
        result.addProperty("y", player.getY());
        result.addProperty("z", player.getZ());
        result.addProperty("yaw", player.getYRot());
        result.addProperty("pitch", player.getXRot());
        result.addProperty("onGround", player.onGround());
        result.addProperty("health", player.getHealth());
        result.addProperty("dimension", player.level().dimension().identifier().toString());
        result.addProperty("gamemode", Minecraft.getInstance().gameMode.getPlayerMode().getName());
        Vec3 v = player.getDeltaMovement();
        JsonObject velocity = new JsonObject();
        velocity.addProperty("x", v.x);
        velocity.addProperty("y", v.y);
        velocity.addProperty("z", v.z);
        result.add("velocity", velocity);
        JsonObject food = new JsonObject();
        food.addProperty("level", player.getFoodData().getFoodLevel());
        food.addProperty("saturation", player.getFoodData().getSaturationLevel());
        result.add("food", food);
        return result;
    }
}
