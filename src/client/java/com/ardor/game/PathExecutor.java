package com.ardor.game;

import com.ardor.client.StatusIndicator;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

import java.util.List;

/**
 * Drives a LocalPlayer along a precomputed path by simulating movement input
 * every client tick.
 *
 * CONFIRMED via javap disassembly of the MC 26.1.2 client jar (not the
 * original assumption, which was wrong -- see the fix note below):
 * LocalPlayer.input is a public ClientInput field, and the field that
 * actually drives movement physics is ClientInput.moveVector (a cached,
 * already-normalized strafe/forward Vec2), NOT keyPresses directly.
 * LocalPlayer.applyInput() reads input.getMoveVector() into the entity's
 * real xxa/zza movement fields, and separately reads keyPresses.jump() into
 * the `jumping` flag that the physics step consumes -- both AFTER
 * ClientInput.tick() has already run for that game tick (LocalPlayer.aiStep()
 * calls input.tick() near the top, applyInput() gets invoked much later via
 * travel()). KeyboardInput.tick() (the real subclass used for actual
 * keyboard/mouse play) unconditionally REBUILDS both keyPresses and
 * moveVector from live Options.key*.isDown() state every tick, discarding
 * anything written to them beforehand.
 *
 * FIXED 2026-08-31: the original version of this class only ever assigned
 * keyPresses (an Input record) once per tick via END_CLIENT_TICK, and never
 * touched moveVector at all. Confirmed live: the bot correctly computed
 * paths and started digging on obstacles, but genuinely never walked or
 * jumped anywhere on its own ("it works if I walk forward/jump for it") --
 * because our keyPresses write was always stale by the time
 * KeyboardInput.tick() next ran (before applyInput() ever read it), and
 * moveVector -- the field applyInput() actually uses for movement magnitude
 * -- was never set by us at all. The fix: swap player.input to a bare
 * ClientInput() (its tick() is a no-op, confirmed via disassembly) while
 * actively driving, and set BOTH keyPresses and moveVector ourselves every
 * tick, restoring the player's real KeyboardInput the moment we're idle so
 * manual control comes back immediately. moveVector is protected in vanilla
 * -- widened to accessible via ardor.accesswidener.
 *
 * Turning uses RotationUtil.smoothLookAt (capped at WALK_TURN_RATE
 * degrees/tick) instead of snapping straight to the target bearing every
 * tick -- since "forward" is relative to the player's current yaw, not
 * world space, a lagging yaw means actual movement traces a curve toward
 * the waypoint rather than a straight line, which reads as natural
 * (pursuit-curve) rather than as the bot cutting the corner short.
 *

 * Dig-through-obstacles: when the next waypoint requires clearing blocks
 * first (BlockWorldMovement.blocksToDig, populated only from
 * config.breakableBlocks), movement pauses and BlockBreaker chews through
 * them one at a time -- reusing its ToolSelector auto-equip -- before
 * resuming toward that waypoint. The breakable-blocks check runs again here
 * at execution time (via blocksToDig), not just during the initial A* plan,
 * so a block that changed since planning (e.g. someone else mined it, or it
 * was never actually breakable) can't cause a silent wrong break.
 */
public final class PathExecutor {

    private static final double ARRIVE_XZ = 0.3;
    private static final double ARRIVE_Y = 0.6;
    private static final float WALK_TURN_RATE = 15f; // degrees/tick -- caps how fast the bot turns to face its next waypoint instead of snapping

    private List<BlockPos> path;
    private BlockWorldMovement movement;
    private int index;
    private Runnable onDone;
    private boolean registered;

    private final BlockBreaker breaker = new BlockBreaker();
    private List<BlockPos> pendingDigs;
    private boolean digging;

    private ClientInput originalInput;
    private boolean inputSwapped;

    /** Starts following waypoints; onDone (nullable) fires once the last one is reached. */
    public void follow(List<BlockPos> waypoints, BlockWorldMovement movement, Runnable onDone) {
        this.path = waypoints;
        this.movement = movement;
        this.index = 0;
        this.onDone = onDone;
        this.pendingDigs = null;
        this.digging = false;
        ensureRegistered();
    }

    public void cancel() {
        this.path = null;
        this.pendingDigs = null;
        this.digging = false;
        breaker.cancel();
        clearInput();
        restoreInput();
    }

    public boolean isActive() {
        return path != null;
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
            System.err.println("[ardor] PathExecutor tick failed, cancelling: " + e);
            path = null;
            clearInput();
            restoreInput();
        }
    }

    private void tickInner(Minecraft client) {
        LocalPlayer player = client.player;
        if (path == null || player == null || index >= path.size()) {
            clearInput();
            restoreInput();
            return;
        }

        ensureInputSwapped(player);
        BlockPos target = path.get(index);

        if (pendingDigs == null) {
            pendingDigs = movement.blocksToDig(target);
        }
        if (!pendingDigs.isEmpty()) {
            clearInput();
            if (!digging) {
                digging = true;
                BlockPos toBreak = pendingDigs.get(0);
                System.err.println("[ardor] path: digging through " + toBreak + " to reach waypoint " + target);
                StatusIndicator.show("Digging through obstacle...");
                breaker.breakBlock(toBreak, () -> {
                    pendingDigs.remove(0);
                    digging = false;
                });
            }
            return;
        }

        double dx = (target.getX() + 0.5) - player.getX();
        double dz = (target.getZ() + 0.5) - player.getZ();
        double dy = target.getY() - player.getY();
        double distXZ = Math.sqrt(dx * dx + dz * dz);

        if (distXZ < ARRIVE_XZ && Math.abs(dy) < ARRIVE_Y) {
            index++;
            pendingDigs = null;
            if (index >= path.size()) {
                Runnable done = onDone;
                path = null;
                clearInput();
                restoreInput();
                if (done != null) done.run();
            }
            return;
        }

        RotationUtil.smoothLookAt(player, target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, WALK_TURN_RATE);

        boolean jump = dy > 0.5;
        player.input.keyPresses = new Input(true, false, false, false, jump, false, true);
        player.input.moveVector = new Vec2(0f, 1f); // (strafe, forward) -- straight forward, no strafing
    }

    /** Replaces the player's real (keyboard-driven) input with an inert one we fully control, saving it to restore later. */
    private void ensureInputSwapped(LocalPlayer player) {
        if (inputSwapped) return;
        originalInput = player.input;
        player.input = new ClientInput(); // base class's tick() is a no-op -- confirmed via disassembly, nothing will stomp our writes
        inputSwapped = true;
    }

    /** Hands real keyboard control back to the player the moment we're not actively driving. */
    private void restoreInput() {
        if (!inputSwapped) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) player.input = originalInput;
        originalInput = null;
        inputSwapped = false;
    }

    private void clearInput() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.input.keyPresses = new Input(false, false, false, false, false, false, false);
            player.input.moveVector = Vec2.ZERO;
        }
    }
}
