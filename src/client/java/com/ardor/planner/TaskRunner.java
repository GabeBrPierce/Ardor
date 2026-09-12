package com.ardor.planner;

import com.google.gson.JsonObject;
import com.ardor.game.ActionDispatcher;
import com.ardor.game.GameActionController;
import com.ardor.game.PathfindingController;
import com.ardor.history.ActionHistory;
import com.ardor.ir.AsciiActionCodec;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * Sequentially executes a decomposed plan (see TaskPlanner), dispatching one
 * ascii command at a time through the exact same ActionDispatcher pipeline
 * voice commands use, and waiting for it to finish before starting the next.
 *
 * "Finish" is a heuristic, not a real completion callback -- neither
 * PathfindingController nor GameActionController were built with one (see
 * their isBusy() additions, made for this). After dispatching, this waits
 * SETTLE_TICKS then polls isBusy() every tick until it goes false or
 * MAX_WAIT_TICKS elapses. Works cleanly for goto/mine (both go busy
 * synchronously within dispatch, or fail synchronously with nothing to wait
 * for) and for one-shot verbs (place/equip/attack-once/drop/use/chat/stop,
 * which never go busy at all -- the runner advances almost immediately).
 * `follow` is deliberately NOT waited on in practice: it's open-ended by
 * design and never finishes on its own, and its busy flag doesn't even go
 * true until the tick after dispatch (PathfindingController.ensureFollowLoop's
 * lazy first iteration) -- so a plan step of "flw @p" reads as instantly
 * done and the runner moves on, leaving the follow running in the
 * background. craft/smelt aren't implemented at all yet (see TODO.md); a
 * plan step using them fails loudly through the normal error path below.
 */
public final class TaskRunner {

    public interface Listener {
        void onTaskStarted(int taskIndex, PlannedTask task);
        void onCommandStarted(int taskIndex, int commandIndex, String command);
        void onCommandFailed(int taskIndex, int commandIndex, String command, String error);
        void onTaskFinished(int taskIndex);
        void onPlanFinished();

        /** A read-only command (currently just `query`, see QueryController) produced a result. Default no-op -- added after the other methods already had implementers, none of which need to care unless they want to (TaskOrchestrator does, for its progress log). */
        default void onCommandResult(int taskIndex, int commandIndex, String result) {}
    }

    private static final int SETTLE_TICKS = 1;
    private static final int MAX_WAIT_TICKS = 20 * 60 * 10; // 10 minutes safety net per command

    private List<PlannedTask> tasks;
    private Listener listener;
    private int taskIndex;
    private int commandIndex;
    private boolean active;
    private boolean paused;
    private boolean waitingForIdle;
    private int settleTicksLeft;
    private int waitTicks;
    private boolean registered;

    private String source = "planner";

    private List<PlannedTask> savedTasks;
    private Listener savedListener;
    private String savedSource;

    private static final TaskRunner SHARED = new TaskRunner();

    /** The single TaskRunner instance TaskPlannerScreen and EventHookDispatcher both drive, so an event-triggered interrupt affects (and the UI reflects) the same run. */
    public static TaskRunner shared() {
        return SHARED;
    }

    /**
     * For event-driven task hooks (see event/EventHookDispatcher): cancels
     * whatever's currently in flight (dispatches `stop`, same as the agent
     * channel's soft restart op) and runs urgentTasks immediately, resuming
     * the previously-active plan afterward once the urgent one finishes.
     * SIMPLIFICATION, documented rather than hidden: "resume" restarts the
     * interrupted task from its own first command, not from the exact
     * command it was on -- true mid-command pause/resume would need
     * PathfindingController/GameActionController to expose real progress
     * checkpoints, which they don't. If interrupt() is called again while
     * already resuming a previously-saved plan, the saved plan is replaced
     * (not stacked) -- only one level of "resume after this" is kept.
     */
    public void interrupt(List<PlannedTask> urgentTasks, Listener urgentListener) {
        interrupt(urgentTasks, urgentListener, "planner");
    }

    public void interrupt(List<PlannedTask> urgentTasks, Listener urgentListener, String source) {
        if (active) {
            savedTasks = tasks;
            savedListener = listener;
            savedSource = this.source;
            JsonObject stop = new JsonObject();
            stop.addProperty("action", "stop");
            try {
                ActionDispatcher.execute(stop);
            } catch (RuntimeException e) {
                System.err.println("[ardor] TaskRunner interrupt: failed to stop in-flight action: " + e);
            }
        }
        run(urgentTasks, wrapWithResume(urgentListener), source);
    }

    private Listener wrapWithResume(Listener inner) {
        return new Listener() {
            @Override public void onTaskStarted(int taskIndex, PlannedTask task) { inner.onTaskStarted(taskIndex, task); }
            @Override public void onCommandStarted(int taskIndex, int commandIndex, String command) { inner.onCommandStarted(taskIndex, commandIndex, command); }
            @Override public void onCommandFailed(int taskIndex, int commandIndex, String command, String error) { inner.onCommandFailed(taskIndex, commandIndex, command, error); }
            @Override public void onCommandResult(int taskIndex, int commandIndex, String result) { inner.onCommandResult(taskIndex, commandIndex, result); }
            @Override public void onTaskFinished(int taskIndex) { inner.onTaskFinished(taskIndex); }
            @Override public void onPlanFinished() {
                inner.onPlanFinished();
                List<PlannedTask> toResume = savedTasks;
                Listener toResumeListener = savedListener;
                String toResumeSource = savedSource;
                savedTasks = null;
                savedListener = null;
                savedSource = null;
                if (toResume != null) run(toResume, toResumeListener, toResumeSource);
            }
        };
    }

    /** tasks must be non-empty (TaskPlanner.parse guarantees this). Defensively copied -- addTask/removeTaskAt mutate this runner's own list, not whatever the caller happened to pass in. */
    public void run(List<PlannedTask> tasks, Listener listener) {
        run(tasks, listener, "planner");
    }

    /** source is attributed on each dispatched command's ActionHistory entry ("planner" for the Task Manager UI, "llm" for say_do voice/chat commands routed here for sequencing -- see ResponseHandler). */
    public void run(List<PlannedTask> tasks, Listener listener, String source) {
        this.tasks = new ArrayList<>(tasks);
        this.listener = listener;
        this.source = source;
        this.taskIndex = 0;
        this.commandIndex = 0;
        this.active = true;
        this.paused = false;
        this.waitingForIdle = false;
        ensureRegistered();
        listener.onTaskStarted(0, this.tasks.get(0));
    }

    public void cancel() {
        active = false;
        paused = false;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isPaused() {
        return paused;
    }

    /**
     * Real halt-in-place, distinct from cancel(): dispatches the same `stop` action
     * interrupt() uses (cancels whatever's actually in flight -- Baritone nav, block
     * breaking, attacking, macro playback, see PathfindingController.handleStop) but keeps
     * taskIndex/commandIndex/tasks exactly where they are, so resume() picks the plan back
     * up at the same command rather than losing queue position (cancel() loses it; this
     * doesn't). No-op if nothing is running or already paused.
     */
    public synchronized void pause() {
        if (!active || paused) return;
        paused = true;
        if (waitingForIdle) {
            JsonObject stop = new JsonObject();
            stop.addProperty("action", "stop");
            try {
                ActionDispatcher.execute(stop);
            } catch (RuntimeException e) {
                System.err.println("[ardor] TaskRunner pause: failed to stop in-flight action: " + e);
            }
            // The in-flight command was just aborted via stop, not completed -- clear
            // waitingForIdle so resume() re-dispatches this same command fresh instead of
            // treating the aborted stop as the command having finished.
            waitingForIdle = false;
        }
    }

    /** Resumes ticking from exactly where pause() left off (same taskIndex/commandIndex). No-op if not currently paused. */
    public synchronized void resume() {
        if (!active || !paused) return;
        paused = false;
    }

    /** Snapshot for remote observers (the bridge's runner.status query) -- keeps taskIndex/commandIndex/tasks private rather than exposing accessors for each. */
    public synchronized JsonObject status() {
        JsonObject result = new JsonObject();
        result.addProperty("active", active);
        result.addProperty("paused", paused);
        if (active && tasks != null && taskIndex < tasks.size()) {
            PlannedTask task = tasks.get(taskIndex);
            result.addProperty("taskDescription", task.description());
            result.addProperty("taskIndex", taskIndex);
            result.addProperty("totalTasks", tasks.size());
            if (commandIndex < task.commands().size()) {
                result.addProperty("command", task.commands().get(commandIndex));
            }
        }
        return result;
    }

    /**
     * "The AI should have a command it has access to to create new tasks" --
     * appends to the END of the currently-running plan (dispatched via the
     * `tadd` ascii verb, see AsciiActionCodec/ActionDispatcher). No-ops
     * quietly if nothing is running (there's no queue to append to).
     */
    public synchronized void addTask(PlannedTask task) {
        if (!active || tasks == null) return;
        tasks.add(task);
    }

    /**
     * "...and delete tasks as well" -- removes a not-yet-finished task by
     * index (0 = the currently-active task). Removing the currently-active
     * task skips its remaining commands on the next tick (tickInner's own
     * bounds-check loop already handles an index that's now past the
     * shrunk list, or pointing at a different task than a moment ago).
     * Returns false (no-op) for an out-of-range index or no active run.
     */
    public synchronized boolean removeTaskAt(int index) {
        if (!active || tasks == null || index < 0 || index >= tasks.size()) return false;
        tasks.remove(index);
        if (index < taskIndex) {
            taskIndex--;
        } else if (index == taskIndex) {
            commandIndex = 0;
        }
        return true;
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
            tickInner();
        } catch (RuntimeException e) {
            System.err.println("[ardor] TaskRunner tick failed, stopping plan: " + e);
            active = false;
        }
    }

    private void tickInner() {
        if (!active || paused) return;

        if (waitingForIdle) {
            if (settleTicksLeft > 0) {
                settleTicksLeft--;
                return;
            }
            if (isBusy()) {
                if (++waitTicks > MAX_WAIT_TICKS) {
                    listener.onCommandFailed(taskIndex, commandIndex, currentCommand(), "timed out waiting for completion");
                    advance();
                }
                return;
            }
            // isBusy() going false isn't the same as "succeeded" -- an async op (mine, shaft) can
            // give up partway through (block not found, drifted out of reach, hit a non-diggable
            // block) with nothing left running and no exception thrown, which used to read as a
            // silent success. See PathfindingController.consumeLastFailure's own javadoc.
            String failure = PathfindingController.consumeLastFailure();
            if (failure != null) {
                listener.onCommandFailed(taskIndex, commandIndex, currentCommand(), failure);
            }
            advance();
            return;
        }

        // Skip past any task(s) with no commands, or a just-finished task, before dispatching.
        while (active && commandIndex >= tasks.get(taskIndex).commands().size()) {
            finishCurrentTaskAndAdvance();
            if (!active) return;
        }
        dispatchCurrent();
    }

    private String currentCommand() {
        return tasks.get(taskIndex).commands().get(commandIndex);
    }

    private void dispatchCurrent() {
        String command = currentCommand();
        listener.onCommandStarted(taskIndex, commandIndex, command);
        try {
            JsonObject action = AsciiActionCodec.decode(command);
            ActionHistory.log(action, source);
            String result = ActionDispatcher.execute(action);
            if (result != null) listener.onCommandResult(taskIndex, commandIndex, result);
        } catch (RuntimeException e) {
            listener.onCommandFailed(taskIndex, commandIndex, command, e.getMessage());
            advance();
            return;
        }
        waitingForIdle = true;
        settleTicksLeft = SETTLE_TICKS;
        waitTicks = 0;
    }

    private void advance() {
        waitingForIdle = false;
        commandIndex++;
    }

    private void finishCurrentTaskAndAdvance() {
        listener.onTaskFinished(taskIndex);
        taskIndex++;
        commandIndex = 0;
        if (taskIndex >= tasks.size()) {
            active = false;
            listener.onPlanFinished();
            return;
        }
        listener.onTaskStarted(taskIndex, tasks.get(taskIndex));
    }

    private static boolean isBusy() {
        return PathfindingController.isBusy() || GameActionController.isBusy();
    }
}
