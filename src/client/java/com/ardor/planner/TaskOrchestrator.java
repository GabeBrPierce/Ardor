package com.ardor.planner;

import com.ardor.client.StatusIndicator;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * "The idea is that the AI should produce some kind of achievable goal with
 * every task, and can keep piping commands to the client until that goal is
 * complete... vague instructions like 'beat minecraft' it should be able to
 * orchestrate itself all the way to beating minecraft."
 *
 * Drives TaskRunner in a loop instead of a single decomposition pass: plan
 * an initial batch for the goal (TaskPlanner.plan), run it, and when the
 * queue empties, ask the model again (TaskPlanner.planNext) with a log of
 * what's happened, whether the goal is now complete. Repeats until the
 * model says goalComplete, MAX_ROUNDS is hit, or the user cancels.
 *
 * MAX_ROUNDS is a real, deliberate safety cap, not an incidental limit --
 * "beat minecraft" is not going to actually finish in 30 rounds of a 3B
 * local model. It exists so a model that never converges on "done" (a
 * stuck loop, a goal it keeps misjudging as incomplete) can't run
 * unattended forever burning LLM calls and real-world bot actions. This is
 * a genuine limitation of what's built here, not a hidden one -- see
 * TODO.md.
 */
public final class TaskOrchestrator {

    private static final int MAX_ROUNDS = 30;

    public interface Listener extends TaskRunner.Listener {
        void onOrchestrationStatus(String status);
    }

    private static boolean active;
    private static String goal;
    private static final List<String> progressLog = new ArrayList<>();
    private static int round;
    private static Listener uiListener;
    private static List<PlannedTask> currentRoundTasks = List.of();

    private TaskOrchestrator() {}

    public static boolean isActive() {
        return active;
    }

    /** The task list for whichever round is currently running -- UI screens use this to keep their own display in sync as rounds change. */
    public static List<PlannedTask> currentRoundTasks() {
        return currentRoundTasks;
    }

    public static void start(String goalText, Listener listener) {
        if (active || TaskRunner.shared().isActive()) return;
        active = true;
        goal = goalText;
        progressLog.clear();
        round = 0;
        uiListener = listener;
        listener.onOrchestrationStatus("Planning initial task(s)...");

        TaskPlanner.plan(goal)
                .thenAccept(tasks -> Minecraft.getInstance().execute(() -> runRound(tasks)))
                .exceptionally(err -> {
                    Minecraft.getInstance().execute(() -> fail("Initial planning failed: " + describeError(err)));
                    return null;
                });
    }

    /**
     * Same self-correcting loop as start(), but for a plan the caller already
     * has (TaskPlannerScreen's plain "Run" button, running whatever Send
     * produced -- possibly reordered/edited since) instead of planning fresh
     * from a goal string. goalText is still needed even though round 1 skips
     * planning: continueOrchestration's re-planning prompt after a failure
     * needs "Original goal: ..." same as the start()-driven path. "We still
     * don't send the error back to the LLM. It needs to basically keep
     * trying to produce tasks until they are fixed" -- confirmed live: Run
     * used TaskRunner directly with no feedback loop at all, only Auto-Run
     * (start()) had one; this closes that gap for Run without duplicating
     * the round/progress-log machinery.
     */
    public static void startWithTasks(String goalText, List<PlannedTask> initialTasks, Listener listener) {
        if (active || TaskRunner.shared().isActive()) return;
        active = true;
        goal = goalText;
        progressLog.clear();
        round = 0;
        uiListener = listener;
        runRound(initialTasks);
    }

    public static void stop() {
        if (!active) return;
        active = false;
        TaskRunner.shared().cancel();
        uiListener.onOrchestrationStatus("Stopped by user.");
    }

    /** Pauses whichever round is currently running -- delegates to the shared TaskRunner, which is the one thing actually driving commands regardless of round. */
    public static void pause() {
        if (!active) return;
        TaskRunner.shared().pause();
        uiListener.onOrchestrationStatus("Paused (round " + round + ").");
    }

    public static void resume() {
        if (!active) return;
        TaskRunner.shared().resume();
        uiListener.onOrchestrationStatus("Round " + round + ": resumed.");
    }

    public static boolean isPaused() {
        return TaskRunner.shared().isPaused();
    }

    private static void runRound(List<PlannedTask> tasks) {
        if (!active) return;
        round++;
        currentRoundTasks = tasks;
        if (tasks.isEmpty()) {
            // The model returned goalComplete:false but zero tasks -- avoid a silent stall by
            // just asking again rather than treating this as "nothing to do, stop."
            continueOrchestration();
            return;
        }
        TaskRunner.shared().run(tasks, wrap());
    }

    private static TaskRunner.Listener wrap() {
        return new TaskRunner.Listener() {
            @Override public void onTaskStarted(int taskIndex, PlannedTask task) {
                uiListener.onTaskStarted(taskIndex, task);
                uiListener.onOrchestrationStatus("Round " + round + ": " + task.description());
            }
            @Override public void onCommandStarted(int taskIndex, int commandIndex, String command) {
                uiListener.onCommandStarted(taskIndex, commandIndex, command);
            }
            @Override public void onCommandFailed(int taskIndex, int commandIndex, String command, String error) {
                uiListener.onCommandFailed(taskIndex, commandIndex, command, error);
                progressLog.add("FAILED: " + command + " -- " + error);
            }
            @Override public void onCommandResult(int taskIndex, int commandIndex, String result) {
                uiListener.onCommandResult(taskIndex, commandIndex, result);
                progressLog.add("RESULT: " + result);
            }
            @Override public void onTaskFinished(int taskIndex) {
                uiListener.onTaskFinished(taskIndex);
                if (taskIndex < currentRoundTasks.size()) {
                    progressLog.add("DONE: " + currentRoundTasks.get(taskIndex).description());
                }
            }
            @Override public void onPlanFinished() {
                uiListener.onPlanFinished();
                if (!active) return;
                continueOrchestration();
            }
        };
    }

    private static void continueOrchestration() {
        if (!active) return;
        if (round >= MAX_ROUNDS) {
            fail("Stopped after " + MAX_ROUNDS + " rounds (safety cap) without the goal being marked complete.");
            return;
        }
        uiListener.onOrchestrationStatus("Checking progress toward: " + goal + " (round " + (round + 1) + ")");
        TaskPlanner.planNext(goal, String.join("\n", progressLog))
                .thenAccept(result -> Minecraft.getInstance().execute(() -> {
                    if (result.goalComplete()) {
                        active = false;
                        uiListener.onOrchestrationStatus("Goal complete: " + goal);
                        StatusIndicator.show("Goal complete: " + goal);
                        return;
                    }
                    progressLog.add("Round " + round + ": planned " + result.tasks().size() + " more task(s)");
                    runRound(result.tasks());
                }))
                .exceptionally(err -> {
                    Minecraft.getInstance().execute(() -> fail("Re-planning failed: " + describeError(err)));
                    return null;
                });
    }

    private static void fail(String message) {
        active = false;
        uiListener.onOrchestrationStatus(message);
        StatusIndicator.show(message);
    }

    private static String describeError(Throwable err) {
        Throwable cause = err.getCause() != null ? err.getCause() : err;
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
