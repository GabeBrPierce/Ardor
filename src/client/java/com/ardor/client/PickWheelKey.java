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
 *
 * tick() bails out entirely whenever a screen is open ("any screen owns input entirely once
 * open" -- see the early return below). Real bug this fixed: picking "Radius"/"Corners" from the
 * Area Selection sub-wheel appeared to do nothing. This class's own hold/tap tracking (wasDown/
 * wheelOpened) kept running via the Fabric tick event the whole time ANY wheel was open,
 * independently of ArdorWheelScreen's own mouseReleased. A quick click on the SUB-wheel is a
 * brand new press/release cycle from this class's point of view: the press reset wheelOpened back
 * to false (wheelOpened was still true from opening the PARENT wheel), so the release satisfied
 * `!isDown && wasDown && !wheelOpened` and ran the tap fallback -- on the SAME click that
 * ArdorWheelScreen.mouseReleased had just used to call AreaSelectionMode.startRadius()/
 * startCorners(). Since AreaSelectionMode.active was now true, tryConfirm() no longer early-
 * returned: for Radius it immediately called stop() (previewBox hadn't even been computed yet by
 * AreaSelectionMode's own ticker, so there was nothing to show for it -- mode flipped on then off
 * within the same click); for Corners it silently advanced cornerPhase one step early. Either way,
 * the wedge you clicked looked like it did nothing. The top-level wheel never showed this because
 * PickWheelKey's OWN hold gesture is what opens it in the first place -- wheelOpened is still true
 * from that same press/hold when its release arrives, so the fallback correctly stays suppressed;
 * it's only a SECOND, freshly-opened screen (a nested wheel) receiving its OWN fresh press/release
 * cycle that exposed this.
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
        // Any screen already open owns middle-click entirely from here (ArdorWheelScreen's own
        // mouseReleased) -- see class doc for the bug this avoids. wasDown/wheelOpened are simply
        // left untouched while a screen is up, so they resync cleanly against real physical state
        // the next time this runs with no screen open, rather than reacting to clicks a screen is
        // already handling itself.
        if (client.gui.screen() != null) return;

        boolean isDown = client.options.keyPickItem.isDown();
        long now = System.currentTimeMillis();

        if (isDown && !wasDown) {
            pressStartMs = now;
            wheelOpened = false;
        } else if (isDown && !wheelOpened && now - pressStartMs >= HOLD_THRESHOLD_MS) {
            wheelOpened = true;
            var subWheel = SingleSelectionMode.entitySubWheelOptions();
            if (subWheel == null) subWheel = SingleSelectionMode.blockSubWheelOptions();
            client.gui.setScreen(subWheel != null ? new ArdorWheelScreen(subWheel) : ArdorWheelScreen.mainWheel());
        } else if (!isDown && wasDown && !wheelOpened) {
            if (!SingleSelectionMode.tryGoHere() && !SingleSelectionMode.tryEditContainer() && !AreaSelectionMode.tryConfirm()) {
                client.pickBlockOrEntity();
            }
        }
        wasDown = isDown;
    }
}
