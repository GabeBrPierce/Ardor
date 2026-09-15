package com.ardor.client;

import com.ardor.game.ActionDispatcher;
import com.ardor.game.BreakAreaController;
import com.ardor.game.KillAllController;
import com.ardor.planner.TaskOrchestrator;
import com.ardor.planner.TaskRunner;
import com.ardor.script.ScriptEngine;

/**
 * A real "stop everything, right now" -- the concrete gap behind "I couldn't stop it" (the bot
 * auto-attacked a friend's piglin and there was no quick, reliable way to make it stop). Confirmed
 * by reading the actual code, not assumed: TaskRunner.cancel() only flips its own active/paused
 * flags -- it does NOT halt whatever's actually in flight (Baritone nav, an in-progress attack,
 * block breaking). Only TaskRunner.pause() does that, by dispatching a real `stop` action first.
 * TaskOrchestrator.stop() also only ever calls TaskRunner.cancel() (not pause() 's stop-dispatch),
 * and is itself a no-op if TaskOrchestrator was never the thing driving the current action (e.g.
 * ResponseHandler's SAY:/DO: path dispatches straight to TaskRunner, bypassing TaskOrchestrator
 * entirely). So neither "cancel" path alone was ever guaranteed to actually stop live combat.
 *
 * "I can stop it but it immediately resolves" -- a SECOND real gap, found the same way: Break
 * Blocks Within (BreakAreaController) and Kill All/Kill Hostile Mobs (KillAllController) are both
 * ENTIRELY independent of TaskRunner/TaskOrchestrator -- AreaSelectionMode calls them directly, so
 * this method never touched either one before. Cancelling only the shared TaskRunner left both of
 * these running completely unaffected: the real `stop` action above would abort whatever single
 * Baritone nav/break was in flight AT THAT INSTANT, but each controller's own tick-driven loop
 * (`next()`/its re-scan) would just immediately pick right back up with its next queued block or
 * re-target -- reading exactly like "I stopped it and it immediately resolved right back to what
 * it was doing."
 */
public final class PanicStop {

    private PanicStop() {}

    public static void now() {
        try {
            ActionDispatcher.execute("stop why:panic_stop");
        } catch (RuntimeException e) {
            System.err.println("[ardor] panic stop: failed to dispatch stop action: " + e);
        }
        TaskRunner.shared().cancel();
        TaskOrchestrator.stop();
        ScriptEngine.cancel();
        BreakAreaController.cancel();
        KillAllController.stop();
        StatusIndicator.show("STOPPED (panic stop)");
    }
}
