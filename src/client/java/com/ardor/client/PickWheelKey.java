package com.ardor.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * Middle-click / vanilla "Pick Block" (Options.keyPickItem), turned into hold-to-open-a-wheel:
 * a tap still does the normal vanilla pick-block action; holding past HOLD_THRESHOLD_MS opens
 * ArdorWheelScreen instead.
 *
 * PickBlockMixin unconditionally swallows vanilla's own automatic pickBlockOrEntity() call inside
 * handleKeybinds() (see its class doc for why that has to be unconditional -- our own tick hook
 * runs too late in the frame to make that decision live). So THIS class is now the sole place
 * that ever calls pickBlockOrEntity() (access-widened via ardor.accesswidener): on release, if
 * the hold threshold was never reached, it performs the tap's pick-block itself, one tick after
 * the physical release rather than instantly on press -- imperceptible at 20 ticks/sec, and the
 * price of avoiding the ordering race entirely.
 *
 * Supersedes the old PingKey.java: SingleSelectionMode now owns the "what's being looked at"
 * context text and highlight/hologram state that PingKey used to produce while held.
 *
 * A tap while SingleSelectionMode is showing a ground-aligned hologram triggers "Go Here" instead
 * of vanilla pick-block, and a tap while it's highlighting a container triggers the container
 * edit-hook instead (SingleSelectionMode.tryGoHere()/tryEditContainer() -- both return false,
 * falling through in order to the next one and finally to normal pick-block, whenever they don't
 * apply: looking at an entity, within touch range, or Alt held for the hologram case -- see that
 * class's doc for why).
 *
 * A HOLD while SingleSelectionMode is highlighting an entity opens that entity's sub-wheel
 * (SingleSelectionMode.entitySubWheelOptions() -- follow/kill/kill all/defend) instead of the
 * main wheel; entitySubWheelOptions() returns null for every other target, so the main wheel is
 * what opens otherwise, same as before this existed.
 *
 * A tap while AreaSelectionMode is active (Radius or Corners) goes to
 * AreaSelectionMode.tryConfirm() instead -- checked last since it's mutually exclusive with
 * SingleSelectionMode (only one can be active at a time), and unlike the other two, it always
 * returns true while active (a tap mid-selection is always "handled," even if it just shows a
 * status line and doesn't advance yet -- see its own doc).
 */
public final class PickWheelKey {

    private static final long HOLD_THRESHOLD_MS = 200;

    private static boolean wasDown;
    private static long pressStartMs;
    private static boolean wheelOpened;

    private PickWheelKey() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick(client);
            } catch (RuntimeException e) {
                System.err.println("[ardor] pick wheel key failed: " + e);
            }
        });
    }

    private static void tick(Minecraft client) {
        boolean isDown = client.options.keyPickItem.isDown();
        long now = System.currentTimeMillis();

        if (isDown && !wasDown) {
            pressStartMs = now;
            wheelOpened = false;
        } else if (isDown && !wheelOpened && now - pressStartMs >= HOLD_THRESHOLD_MS) {
            wheelOpened = true;
            if (client.screen == null) {
                var subWheel = SingleSelectionMode.entitySubWheelOptions();
                client.setScreen(subWheel != null ? new ArdorWheelScreen(subWheel) : ArdorWheelScreen.mainWheel());
            }
        } else if (!isDown && wasDown && !wheelOpened) {
            if (!SingleSelectionMode.tryGoHere() && !SingleSelectionMode.tryEditContainer() && !AreaSelectionMode.tryConfirm()) {
                client.pickBlockOrEntity();
            }
        }
        wasDown = isDown;
    }
}
