package com.ardor.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Two deliberately DIFFERENT idle signals.
 *
 * ticksSinceInput() counts client ticks since the last genuine hardware keyboard/mouse event. It
 * is fed by KeyboardActivityMixin/MouseActivityMixin, which inject into the private methods MC's
 * own GLFW callbacks dispatch to (KeyboardHandler.keyPress, MouseHandler.onButton/onMove --
 * confirmed by javap against the real 26.1.2 client jar). It has to observe input that far down
 * because this mod drives the player constantly: InputSwapManager swaps player.input for a
 * synthetic ClientInput, and KeybindControl re-asserts KeyMapping.setDown(true) every tick -- so
 * both player.input and KeyMapping.isDown() read as "active" with no human anywhere near the
 * keyboard. Nothing in this mod ever calls into KeyboardHandler/MouseHandler (confirmed by grep),
 * so a callback landing there means a real person touched real hardware.
 *
 * ticksSincePlayerMoved() counts ticks since player.position() last changed at all, whether a
 * human or an Ardor script caused it -- a plain per-tick poll, no mixin needed.
 */
public final class ActivityTracker {

    /** (1.0E-5)^2 -- a strafe that never crosses a block boundary still has to count as movement. */
    private static final double MOVE_EPSILON_SQR = 1.0E-10;

    private static int ticksSinceInput;
    private static int ticksSincePlayerMoved;
    private static Vec3 lastPos;
    private static boolean registered;

    private ActivityTracker() {}

    public static void register() {
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ticksSinceInput++;
            LocalPlayer player = client.player;
            if (player == null) {
                lastPos = null;
                return;
            }
            Vec3 pos = player.position();
            if (lastPos == null || lastPos.distanceToSqr(pos) > MOVE_EPSILON_SQR) {
                ticksSincePlayerMoved = 0;
            } else {
                ticksSincePlayerMoved++;
            }
            lastPos = pos;
        });
    }

    /** Called only from KeyboardActivityMixin/MouseActivityMixin, i.e. only on real hardware input. */
    public static void onRawInput() {
        ticksSinceInput = 0;
    }

    public static int ticksSinceInput() {
        return ticksSinceInput;
    }

    public static int ticksSincePlayerMoved() {
        return ticksSincePlayerMoved;
    }
}
