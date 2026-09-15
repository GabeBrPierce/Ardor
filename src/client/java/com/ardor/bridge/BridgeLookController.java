package com.ardor.bridge;

import com.ardor.client.ArdorMasterToggle;
import com.ardor.game.RotationUtil;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * look.at {x,y,z,maxDegPerTick}: a persistent, mod-side-smoothed look target
 * (see the approved plan) -- ticks RotationUtil.smoothLookAt every frame
 * toward whatever the companion last set, held until changed or cleared,
 * same "held key" pattern BridgeInputController already established for
 * input.set. Keeps the companion from needing to send ~20 look updates/sec
 * over the wire for something this cheap to do locally.
 *
 * look.set (an instant snap, no smoothing) is handled directly in
 * BridgeDispatcher and clears whatever target is held here, since an
 * explicit snap means the companion is taking over aiming itself.
 */
public final class BridgeLookController {

    private BridgeLookController() {}

    private static volatile Double targetX, targetY, targetZ;
    private static volatile float maxDegPerTick = 15f;
    private static boolean registered;

    public static void register() {
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(BridgeLookController::tick);
        ArdorMasterToggle.register(BridgeLookController::clear);
    }

    public static void setTarget(double x, double y, double z, float degPerTick) {
        targetX = x;
        targetY = y;
        targetZ = z;
        maxDegPerTick = degPerTick;
    }

    public static void clear() {
        targetX = null;
        targetY = null;
        targetZ = null;
    }

    private static void tick(Minecraft client) {
        try {
            tickInner(client);
        } catch (RuntimeException e) {
            System.err.println("[ardor] bridge look tick failed: " + e);
            clear();
        }
    }

    private static void tickInner(Minecraft client) {
        if (!ArdorMasterToggle.isEnabled()) return;
        LocalPlayer player = client.player;
        Double x = targetX, y = targetY, z = targetZ;
        if (player == null || x == null || y == null || z == null) return;
        RotationUtil.smoothLookAt(player, x, y, z, maxDegPerTick);
    }
}
