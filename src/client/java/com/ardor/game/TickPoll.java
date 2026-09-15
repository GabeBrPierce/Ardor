package com.ardor.game;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.util.function.BooleanSupplier;

/**
 * Shared "single-fire re-registering tick poll" primitive (Fabric's tick-event API has no
 * unregister). Each listener instance marks itself done unconditionally as the very first
 * statement, before any branching -- getting that ordering backwards is what once caused
 * exponential tick-listener growth (a client freeze) when a "keep waiting" branch chained a new
 * instance without retiring the old one.
 */
public final class TickPoll {

    private TickPoll() {}

    /** Runs action after `ticks` more client ticks (immediately if ticks <= 0). */
    public static void after(int ticks, Runnable action) {
        if (ticks <= 0) {
            action.run();
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(new Countdown(ticks, action));
    }

    /** Polls `condition` once per tick; runs onReady the tick it first returns true, or onTimeout once maxTicks elapse without it. */
    public static void until(int maxTicks, BooleanSupplier condition, Runnable onReady, Runnable onTimeout) {
        step(0, maxTicks, condition, onReady, onTimeout);
    }

    private static void step(int ticksWaited, int maxTicks, BooleanSupplier condition, Runnable onReady, Runnable onTimeout) {
        if (condition.getAsBoolean()) {
            onReady.run();
            return;
        }
        if (ticksWaited >= maxTicks) {
            onTimeout.run();
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(new Step(ticksWaited, maxTicks, condition, onReady, onTimeout));
    }

    private static final class Countdown implements ClientTickEvents.EndTick {
        private int ticksLeft;
        private final Runnable action;
        private boolean done;

        Countdown(int ticksLeft, Runnable action) {
            this.ticksLeft = ticksLeft;
            this.action = action;
        }

        @Override
        public void onEndTick(Minecraft client) {
            if (done) return;
            if (--ticksLeft > 0) return;
            done = true;
            try {
                action.run();
            } catch (RuntimeException e) {
                System.err.println("[ardor] TickPoll.after action failed: " + e);
            }
        }
    }

    private static final class Step implements ClientTickEvents.EndTick {
        private final int ticksWaited;
        private final int maxTicks;
        private final BooleanSupplier condition;
        private final Runnable onReady;
        private final Runnable onTimeout;
        private boolean done;

        Step(int ticksWaited, int maxTicks, BooleanSupplier condition, Runnable onReady, Runnable onTimeout) {
            this.ticksWaited = ticksWaited;
            this.maxTicks = maxTicks;
            this.condition = condition;
            this.onReady = onReady;
            this.onTimeout = onTimeout;
        }

        @Override
        public void onEndTick(Minecraft client) {
            if (done) return;
            done = true; // unconditional, before any branching -- see class doc
            try {
                step(ticksWaited + 1, maxTicks, condition, onReady, onTimeout);
            } catch (RuntimeException e) {
                System.err.println("[ardor] TickPoll.until step failed: " + e);
                onTimeout.run();
            }
        }
    }
}
