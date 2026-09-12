package com.ardor.bridge;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * block.breakStart/block.breakStop: the raw low-level break primitive (see
 * the approved plan). Unlike BlockBreaker (which owns "which block, how
 * long, when to give up" -- the decision logic that moves to the companion
 * app in Phase 2), this is a direct, dumb passthrough: hold a target+face,
 * tick MultiPlayerGameMode.continueDestroyBlock every frame while held
 * (matching real held-left-click, exactly BlockBreaker's own verified
 * mechanics), stop when told to or when the block disappears. No timeout,
 * no reach check, no auto tool-equip -- those are companion-side decisions
 * now.
 *
 * Deviates from the plan's literal "breakStart/breakContinue/breakStop"
 * three-command wire shape: continueDestroyBlock needs to fire once per
 * tick to accumulate progress correctly, which a network round-trip per
 * tick can't reliably deliver -- so breakContinue isn't a companion-facing
 * command at all, it's this class's own internal tick loop, same "held
 * key, mod ticks it" pattern as input.set/look.at.
 */
public final class BridgeBreakController {

    private BridgeBreakController() {}

    private static volatile BlockPos target;
    private static volatile Direction face;
    private static boolean registered;

    public static void register() {
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(BridgeBreakController::tick);
    }

    public static void start(BlockPos pos, Direction requestedFace) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) return;
        target = pos;
        face = requestedFace != null ? requestedFace : faceTowardPlayer(player, pos);
        client.gameMode.startDestroyBlock(pos, face);
    }

    public static void stop() {
        if (target == null) return;
        Minecraft.getInstance().gameMode.stopDestroyBlock();
        target = null;
    }

    public static boolean isActive() {
        return target != null;
    }

    private static void tick(Minecraft client) {
        try {
            tickInner(client);
        } catch (RuntimeException e) {
            System.err.println("[ardor] bridge break tick failed: " + e);
            target = null;
        }
    }

    private static void tickInner(Minecraft client) {
        BlockPos pos = target;
        if (pos == null || client.level == null) return;
        if (client.level.getBlockState(pos).isAir()) {
            target = null; // already gone -- nothing left to continue ticking
            return;
        }
        client.gameMode.continueDestroyBlock(pos, face);
    }

    private static Direction faceTowardPlayer(LocalPlayer player, BlockPos pos) {
        double dx = player.getX() - (pos.getX() + 0.5);
        double dy = player.getEyeY() - (pos.getY() + 0.5);
        double dz = player.getZ() - (pos.getZ() + 0.5);
        double adx = Math.abs(dx), ady = Math.abs(dy), adz = Math.abs(dz);
        if (adx > ady && adx > adz) return dx > 0 ? Direction.EAST : Direction.WEST;
        if (ady > adx && ady > adz) return dy > 0 ? Direction.UP : Direction.DOWN;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
