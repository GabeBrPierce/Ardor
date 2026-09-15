package com.ardor.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Single master on/off switch for all of Ardor's autonomous behavior -- "I want to be able to turn
 * off the whole bot/AI control temporarily." Disabling calls PanicStop.now() for an immediate real
 * halt (in-flight Baritone nav/attack/breaking, TaskRunner, TaskOrchestrator, scripts -- see that
 * class's own doc for exactly what it touches), then stays off: every entry point that could start
 * something NEW checks isEnabled() and no-ops while it's false --
 * ActionDispatcher.execute (covers voice/DO: commands, event-triggered tasks, the plain Task
 * Manager Run/Auto-Run plan steps, and the companion bridge's AgentOps commands, since all of those
 * funnel through it -- see that class's own "single entry point" doc), TaskRunner.run,
 * TaskOrchestrator.start/startWithTasks, and each independent always-on reactive controller
 * (auto-eat/flee/arrow-dodge/sleep/proactive-combat/parry/drowning-safety/social-greeting) plus the
 * companion bridge's direct input/look/break controllers, each checking this at the top of its own
 * tick. Re-enabling doesn't resume anything on its own -- whatever was running when disabled stays
 * stopped; the user has to re-trigger it (Run again, walk back into a fight, etc).
 *
 * Controllers self-register via register(Cancellable) (called from their own register() method,
 * or a static initializer for a class with no init-time register() of its own) instead of being
 * hand-listed here -- see Cancellable.
 */
public final class ArdorMasterToggle {

    private static volatile boolean enabled = true;
    private static final List<Cancellable> CANCELLABLES = new ArrayList<>();

    private ArdorMasterToggle() {}

    public static boolean isEnabled() {
        return enabled;
    }

    /** Self-registration point -- setEnabled(false) calls cancel() on every registered instance. */
    public static void register(Cancellable c) {
        CANCELLABLES.add(c);
    }

    public static void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        if (!value) {
            PanicStop.now(); // real, immediate halt of TaskRunner/TaskOrchestrator/scripts/Baritone/breaking -- see its own doc
            // Called once here, not polled every tick from inside each controller -- some cancel()
            // implementations (e.g. GameActionController.stopAttacking's setSprinting(false)) have
            // real side effects that would otherwise keep firing every tick while disabled.
            for (Cancellable c : CANCELLABLES) c.cancel();
        }
        StatusIndicator.show(value ? "Ardor enabled." : "Ardor disabled.");
    }

    public static void toggle() {
        setEnabled(!enabled);
    }
}
