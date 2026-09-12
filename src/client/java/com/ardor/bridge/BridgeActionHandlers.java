package com.ardor.bridge;

import com.google.gson.JsonObject;
import com.ardor.agent.AgentOps;
import com.ardor.game.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Phase 1 one-shot bridge commands (see the approved plan): block.place,
 * entity.attack, entity.interact, item.use, inventory.selectSlot/moveItem/
 * dropSlot, chat.send/chat.whisper, command.sendRaw, system.restartSoft/
 * relaunch. Each of these completes synchronously within a single dispatch
 * -- unlike input.set/look.at/block.breakStart, there's no "held" state to
 * track here, so no separate controller class per command.
 *
 * Entities are addressed by their raw network id (Entity.getId()), not a
 * selector string -- selector syntax is a higher-level IR concept the
 * companion app owns; a raw id is what world.nearbyEntities query results
 * already carry, so no extra resolution step is needed at this layer.
 *
 * Reuses the exact same verified Minecraft API calls GameActionController
 * already established this session (useItemOn/useItem/attack/interact,
 * direct Inventory field writes for slot ops -- same "likely fine in
 * singleplayer, not verified for multiplayer" caveat carried over), just
 * addressed by raw slot/entity-id instead of item-id/selector lookups,
 * since resolving those is now the companion's job.
 */
final class BridgeActionHandlers {

    private BridgeActionHandlers() {}

    static void blockPlace(JsonObject msg) {
        LocalPlayer player = requirePlayer();
        BlockPos target = new BlockPos(msg.get("x").getAsInt(), msg.get("y").getAsInt(), msg.get("z").getAsInt());
        Direction facing = Direction.valueOf(msg.get("face").getAsString().toUpperCase());
        if (msg.has("heldItemSlot")) selectHotbarSlot(player, msg.get("heldItemSlot").getAsInt());

        BlockPos against = target.relative(facing.getOpposite());
        RotationUtil.lookAtExact(player, target);
        Vec3 hitVec = Vec3.atCenterOf(against);
        BlockHitResult hit = new BlockHitResult(hitVec, facing, against, false);
        Minecraft.getInstance().gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
    }

    static void entityAttack(JsonObject msg) {
        Minecraft mc = Minecraft.getInstance();
        Entity target = requireEntity(msg);
        RotationUtil.lookAtExact(mc.player, target);
        mc.gameMode.attack(mc.player, target);
    }

    static void entityInteract(JsonObject msg) {
        Minecraft mc = Minecraft.getInstance();
        Entity target = requireEntity(msg);
        RotationUtil.lookAtExact(mc.player, target);
        InteractionHand hand = msg.has("hand") ? handOf(msg.get("hand").getAsString()) : InteractionHand.MAIN_HAND;
        mc.gameMode.interact(mc.player, target, new EntityHitResult(target), hand);
    }

    static void itemUse(JsonObject msg) {
        Minecraft mc = Minecraft.getInstance();
        InteractionHand hand = msg.has("hand") ? handOf(msg.get("hand").getAsString()) : InteractionHand.MAIN_HAND;
        mc.gameMode.useItem(mc.player, hand);
    }

    static void inventorySelectSlot(JsonObject msg) {
        selectHotbarSlot(requirePlayer(), msg.get("slot").getAsInt());
    }

    /** Removes count items from `from` and merges them into `to` (fills if empty, tops up a matching stack); whatever doesn't fit goes back into `from` rather than being lost. */
    static void inventoryMoveItem(JsonObject msg) {
        Inventory inv = requirePlayer().getInventory();
        int from = msg.get("from").getAsInt();
        int to = msg.get("to").getAsInt();
        int count = msg.has("count") ? msg.get("count").getAsInt() : Integer.MAX_VALUE;

        ItemStack moving = inv.removeItem(from, count);
        if (moving.isEmpty()) return;

        ItemStack destination = inv.getItem(to);
        ItemStack leftover;
        if (destination.isEmpty()) {
            inv.setItem(to, moving);
            leftover = ItemStack.EMPTY;
        } else if (ItemStack.isSameItemSameComponents(destination, moving)) {
            int room = destination.getMaxStackSize() - destination.getCount();
            int move = Math.min(room, moving.getCount());
            destination.grow(move);
            moving.shrink(move);
            leftover = moving;
        } else {
            leftover = moving;
        }
        if (!leftover.isEmpty()) {
            ItemStack backInFrom = inv.getItem(from);
            if (backInFrom.isEmpty()) {
                inv.setItem(from, leftover);
            } else {
                backInFrom.grow(leftover.getCount());
            }
        }
    }

    static void inventoryDropSlot(JsonObject msg) {
        LocalPlayer player = requirePlayer();
        int slot = msg.get("slot").getAsInt();
        boolean all = msg.has("all") && msg.get("all").getAsBoolean();
        int count = all ? Integer.MAX_VALUE : (msg.has("count") ? msg.get("count").getAsInt() : 1);
        ItemStack removed = player.getInventory().removeItem(slot, count);
        if (!removed.isEmpty()) player.drop(removed, false);
    }

    static void chatSend(JsonObject msg) {
        requirePlayer().connection.sendChat(msg.get("message").getAsString());
    }

    static void chatWhisper(JsonObject msg) {
        LocalPlayer player = requirePlayer();
        Entity target = requireEntity(msg, "player");
        if (!(target instanceof Player targetPlayer)) {
            throw new IllegalStateException("chat.whisper target is not a player");
        }
        player.connection.sendCommand("msg " + targetPlayer.getGameProfile().name() + " " + msg.get("message").getAsString());
    }

    static void commandSendRaw(JsonObject msg) {
        String command = msg.get("command").getAsString();
        if (command.startsWith("/")) command = command.substring(1);
        requirePlayer().connection.sendCommand(command);
    }

    static void systemRestartSoft() {
        AgentOps.run(opJson("restart")).exceptionally(err -> {
            System.err.println("[ardor] system.restartSoft failed: " + err);
            return null;
        });
    }

    static void systemRelaunch() {
        AgentOps.run(opJson("restartGame")).exceptionally(err -> {
            System.err.println("[ardor] system.relaunch failed: " + err);
            return null;
        });
    }

    /**
     * Clean quit, no relaunch -- distinct from system.relaunch (which also spawns a replacement
     * process). Added for the companion app's own Minecraft start/kill capability: killing the
     * process from outside (Process.destroy()) is always a hard TerminateProcess on Windows, no
     * graceful shutdown hook, so a companion-initiated "kill" should prefer asking the game to quit
     * itself first (world save intact) and only fall back to a hard kill if the game is unresponsive.
     * Same Minecraft.stop() call already confirmed via disassembly in AgentOps.restartGame() to be
     * the exact save-and-quit path a real window-close click triggers.
     */
    static void systemShutdown() {
        Minecraft.getInstance().stop();
    }

    private static JsonObject opJson(String op) {
        JsonObject o = new JsonObject();
        o.addProperty("op", op);
        return o;
    }

    private static void selectHotbarSlot(LocalPlayer player, int slot) {
        Inventory inv = player.getInventory();
        if (Inventory.isHotbarSlot(slot)) inv.setSelectedSlot(slot);
    }

    private static InteractionHand handOf(String hand) {
        return "off_hand".equalsIgnoreCase(hand) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    private static Entity requireEntity(JsonObject msg) {
        return requireEntity(msg, "entityId");
    }

    private static Entity requireEntity(JsonObject msg, String field) {
        Level level = requirePlayer().level();
        int id = msg.get(field).getAsInt();
        Entity entity = level.getEntity(id);
        if (entity == null) throw new IllegalStateException("no entity with id " + id);
        return entity;
    }

    private static LocalPlayer requirePlayer() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) throw new IllegalStateException("no client player loaded");
        return player;
    }
}
