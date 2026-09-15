package com.ardor.game;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

/**
 * "Detect whether the user is able to fly. And if they are, allow flying to be a part of
 * navigation." PathfindingController.canFly() (Abilities.mayfly) already detected this with no
 * caller; this is its first real one. Baritone has no creative-flight pathing mode of its own
 * (confirmed via javap against Settings.class -- no allowFlying-shaped setting exists, only
 * elytra-specific ones), so this is a small standalone straight-line flight controller, used
 * INSTEAD of Baritone for plain "go to this position" requests (handleGoto, Go Here) when flight
 * is available -- NOT for walks that need to end up precisely adjacent to a specific block (tool
 * fetch, table walk-up, structure building), which still need real walking so the bot ends up
 * standing on solid ground next to the target, not hovering near it.
 *
 * Movement technique: creative flight in vanilla moves in the player's exact look direction (yaw
 * AND pitch) while holding forward -- unlike walking, which is always horizontal-only. So this
 * just points the player's full look vector directly at the target (RotationUtil.smoothLookAt, the
 * same turn-toward-a-3D-point helper PathExecutor/combat already use) and holds forward+sprint,
 * via the same player.input swap technique PathExecutor established (see its own class doc for why
 * moveVector, not just keyPresses, has to be written every tick -- KeyboardInput.tick() rebuilds
 * both from live key state and would otherwise stomp a synthetic keyPresses-only write).
 *
 * Toggles real flight the same way vanilla's double-jump does: sets Abilities.flying directly then
 * sends the same ServerboundPlayerAbilitiesPacket(Abilities) the client always sends after a local
 * ability change (confirmed via javap: its sole constructor takes an Abilities and reads
 * isFlying() off it) -- not a client-only visual toggle, the server is actually told. Restores
 * flying to whatever it was before on arrival/failure/cancel, so this never leaves the player
 * flying when they weren't already.
 *
 * Does its own direct player.input swap rather than going through InputSwapManager -- PathExecutor
 * (the system this most directly parallels) isn't migrated to that arbiter either yet (see
 * TODO.md), so this matches existing precedent rather than half-solving that gap on its own.
 */
public final class FlightNav {

    private FlightNav() {}

    private static final double ARRIVE_DIST_SQ = 1.2 * 1.2;
    private static final float TURN_RATE = 20f;
    private static final long TIMEOUT_MS = 30_000;

    private static boolean loopRegistered;
    private static boolean active;
    private static ClientInput originalInput;
    private static boolean wasFlyingBefore;
    private static Vec3 target;
    private static Runnable onArrive;
    private static Consumer<String> onFailed;
    private static long deadline;

    /** True only when the player currently has flight PERMITTED (Abilities.mayfly) -- see PathfindingController.canFly()'s own doc for the exact signal and its caveats (an elytra in inventory doesn't count). */
    public static boolean available() {
        return PathfindingController.canFly();
    }

    public static void flyTo(Vec3 destination, Runnable onArrive, Consumer<String> onFailed) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            onFailed.accept("no client player loaded");
            return;
        }
        if (!available()) {
            onFailed.accept("flight isn't available (Abilities.mayfly is false)");
            return;
        }

        if (!active) {
            wasFlyingBefore = player.getAbilities().flying;
            originalInput = player.input;
            player.input = new ClientInput(); // base class's tick() is a no-op -- same confirmed-safe swap PathExecutor uses
            ensureLoop();
        }
        if (!player.getAbilities().flying) {
            player.getAbilities().flying = true;
            player.connection.send(new ServerboundPlayerAbilitiesPacket(player.getAbilities()));
        }
        active = true;
        target = destination;
        FlightNav.onArrive = onArrive;
        FlightNav.onFailed = onFailed;
        deadline = System.currentTimeMillis() + TIMEOUT_MS;
    }

    public static boolean isActive() {
        return active;
    }

    public static void cancel() {
        if (!active) return;
        stop(null);
    }

    private static void ensureLoop() {
        if (loopRegistered) return;
        loopRegistered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!active) return;
            try {
                tick(client);
            } catch (RuntimeException e) {
                System.err.println("[ardor] flight nav tick failed: " + e);
                stop("flight nav tick failed: " + e);
            }
        });
    }

    private static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            stop("player unloaded mid-flight");
            return;
        }
        if (player.position().distanceToSqr(target) <= ARRIVE_DIST_SQ) {
            Runnable arrive = onArrive;
            stop(null);
            if (arrive != null) arrive.run();
            return;
        }
        if (System.currentTimeMillis() > deadline) {
            stop("timed out flying to " + target);
            return;
        }
        RotationUtil.smoothLookAt(player, target.x, target.y, target.z, TURN_RATE);
        player.input.keyPresses = new Input(true, false, false, false, false, false, true);
        player.input.moveVector = new Vec2(0f, 1f); // (strafe, forward) -- straight forward, yaw+pitch already point at the target
    }

    private static void stop(String failReason) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.input = originalInput;
            if (!wasFlyingBefore && player.getAbilities().flying) {
                player.getAbilities().flying = false;
                player.connection.send(new ServerboundPlayerAbilitiesPacket(player.getAbilities()));
            }
        }
        active = false;
        originalInput = null;
        Consumer<String> failed = onFailed;
        onArrive = null;
        onFailed = null;
        if (failReason != null && failed != null) failed.accept(failReason);
    }
}
