package com.ardor.game;

import com.ardor.client.StatusIndicator;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Breaks one block over multiple ticks via MultiPlayerGameMode's
 * startDestroyBlock/continueDestroyBlock, mirroring what happens when a real
 * player holds left-click.
 *
 * CONFIRMED via full bytecode disassembly of continueDestroyBlock and
 * sameDestroyTarget (not just method signatures): progress accumulates from
 * BlockState.getDestroyProgress(player, level, pos) each tick and the method
 * auto-finishes the block once progress reaches 1.0 -- no separate finishing
 * call needed, matching vanilla's own Minecraft.class call sites (which also
 * never call a bare destroyBlock() alongside continueDestroyBlock).
 * sameDestroyTarget only checks position + held item, not orientation.
 *
 * What IS missing from continueDestroyBlock itself: any reach-distance
 * check. It trusts the passed BlockPos blindly client-side; a real client
 * would only be calling it because the crosshair is actually on the block.
 * This class holds no position/orientation while mining, so nothing stops
 * the player drifting out of real reach (water currents/buoyancy are the
 * concrete case that surfaced this: client-side progress ticks up normally
 * with no error, but the eventual finish presumably gets rejected server-side
 * once out of reach, so the block visually never breaks). Now actively looks
 * at the target and cancels if reach is exceeded, instead of silently
 * ticking forever until the timeout. Turning uses RotationUtil.smoothLookAt
 * (capped at LOOK_TURN_RATE degrees/tick) rather than snapping the exact
 * bearing every tick.
 */
public final class BlockBreaker {

    private static final int TIMEOUT_TICKS = 200; // 10s safety net
    private static final double MAX_REACH_SQ = 4.5 * 4.5; // vanilla survival block-reach distance
    private static final float LOOK_TURN_RATE = 20f; // degrees/tick -- see RotationUtil; faster than walking since mining is stationary
    private static final float CREATIVE_FACING_EPSILON_DEG = 3f; // "close enough" to call it looking at the block before an instant creative break

    private BlockPos target;
    private Direction face;
    private int ticks;
    private Runnable onDone;
    private boolean registered;
    private String lastFailureReason;
    private boolean creative;

    public void breakBlock(BlockPos pos, Runnable onDone) {
        Minecraft client = Minecraft.getInstance();
        this.target = pos;
        this.face = faceTowardPlayer(pos);
        this.ticks = 0;
        this.onDone = onDone;
        this.lastFailureReason = null;
        this.creative = client.player.getAbilities().instabuild;
        ToolSelector.equipBestTool(client.player.getInventory(), client.level.getBlockState(pos));
        // Creative mode doesn't go through the progressive startDestroyBlock/continueDestroyBlock
        // sequence at all -- confirmed live: mining silently made zero progress in creative (no
        // exception, no error, block just never broke and the timeout eventually fired). Vanilla's
        // own input handling calls the separate one-shot MultiPlayerGameMode.destroyBlock(pos) for
        // creative instant-break instead; continueDestroyBlock is a no-op-ish survival-only path.
        // Used to snap-look and destroy in the same synchronous call -- read as instant/robotic
        // ("add human-like movements to breaking", see TODO.md). Now shares tickInner's smoothLookAt
        // loop with the survival path below and waits until RotationUtil.isFacing says the turn has
        // actually arrived before firing the instant destroy, same "look, then act" shape every
        // other one-shot-but-humanized action in this codebase (GameActionController's attack timing)
        // already uses instead of a bare snap.
        if (!creative) {
            client.gameMode.startDestroyBlock(pos, face);
        }
        ensureRegistered();
    }

    public void cancel() {
        if (target == null) return;
        Minecraft.getInstance().gameMode.stopDestroyBlock();
        target = null;
        lastFailureReason = "cancelled";
    }

    public boolean isActive() {
        return target != null;
    }

    /** Null if the most recent breakBlock() actually broke the block; otherwise why it didn't (consumed once, then cleared -- same pattern as PathfindingController.consumeLastFailure). */
    public String consumeLastFailure() {
        String reason = lastFailureReason;
        lastFailureReason = null;
        return reason;
    }

    private void ensureRegistered() {
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    // An uncaught exception here crashes the whole client -- confirmed live,
    // see TODO.md and PushToTalk's tick handler. Never let one escape.
    private void tick(Minecraft client) {
        try {
            tickInner(client);
        } catch (RuntimeException e) {
            System.err.println("[ardor] BlockBreaker tick failed, cancelling: " + e);
            target = null;
        }
    }

    private void tickInner(Minecraft client) {
        if (target == null) return;
        MultiPlayerGameMode gameMode = client.gameMode;
        LocalPlayer player = client.player;

        if (client.level.getBlockState(target).isAir()) {
            finish(gameMode);
            return;
        }

        if (distanceSqTo(player, target) > MAX_REACH_SQ) {
            System.err.println("[ardor] mine: drifted out of reach of " + target + ", giving up on this block");
            StatusIndicator.show("Drifted out of reach while mining");
            lastFailureReason = "drifted out of reach while mining " + target;
            finish(gameMode);
            return;
        }

        if (ticks++ > TIMEOUT_TICKS) {
            lastFailureReason = "timed out mining " + target + " (" + TIMEOUT_TICKS + " ticks with no progress)";
            finish(gameMode);
            return;
        }

        RotationUtil.smoothLookAt(player, target, LOOK_TURN_RATE);

        if (creative) {
            if (!RotationUtil.isFacing(player, target, CREATIVE_FACING_EPSILON_DEG)) return; // still turning to face it
            // Confirmed live: destroyBlock silently did nothing when the player wasn't actually
            // facing the target -- this method takes only a BlockPos (no direction/hit info), but
            // apparently still validates against the player's current look direction server-side,
            // the same way a real crosshair-click would. Also check the boolean return value instead
            // of assuming success -- ignoring it was silently treating a failed break as done, which
            // is exactly what caused mine to "complete" with nothing actually broken.
            boolean broke = gameMode.destroyBlock(target);
            lastFailureReason = broke ? null : "creative destroyBlock rejected " + target;
            finish(gameMode);
            return;
        }

        gameMode.continueDestroyBlock(target, face);
    }

    private void finish(MultiPlayerGameMode gameMode) {
        gameMode.stopDestroyBlock();
        Runnable done = onDone;
        target = null;
        if (done != null) done.run();
    }

    private static double distanceSqTo(LocalPlayer player, BlockPos pos) {
        double dx = player.getX() - (pos.getX() + 0.5);
        double dy = player.getEyeY() - (pos.getY() + 0.5);
        double dz = player.getZ() - (pos.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    private Direction faceTowardPlayer(BlockPos pos) {
        LocalPlayer player = Minecraft.getInstance().player;
        double dx = player.getX() - (pos.getX() + 0.5);
        double dy = (player.getEyeY()) - (pos.getY() + 0.5);
        double dz = player.getZ() - (pos.getZ() + 0.5);
        double adx = Math.abs(dx), ady = Math.abs(dy), adz = Math.abs(dz);
        if (adx > ady && adx > adz) return dx > 0 ? Direction.EAST : Direction.WEST;
        if (ady > adx && ady > adz) return dy > 0 ? Direction.UP : Direction.DOWN;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
