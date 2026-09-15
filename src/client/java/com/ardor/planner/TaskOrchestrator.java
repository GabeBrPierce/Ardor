package com.ardor.planner;

import com.ardor.client.ArdorMasterToggle;
import com.ardor.client.StatusIndicator;
import com.ardor.voice.ResponseHandler;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * "The idea is that the AI should produce some kind of achievable goal with
 * every task, and can keep piping commands to the client until that goal is
 * complete... vague instructions like 'beat minecraft' it should be able to
 * orchestrate itself all the way to beating minecraft."
 *
 * Two-stage staged-plan architecture ("develop a plan; another specially trained AI works out the
 * details; the mod executes; failures/updates get sent to the proper layer so the planning AI
 * doesn't lose context over micromanaging details"):
 *   1. This class (the PLANNER layer) asks TaskPlanner.planSteps for an ordered list of small,
 *      plain-English steps -- no ascii grammar, since it never needs to know the command syntax.
 *   2. Each step is handed to its own Micromanager (the MICROMANAGER layer), which turns that ONE
 *      step's plain English into ascii commands and runs them, self-correcting internally across
 *      several rounds if needed.
 *   3. A Micromanager reports back exactly ONE compact line ("Done: ..." / "FAILED: ... -- why")
 *      when it finishes or gives up -- never its own internal retry noise -- which is all this
 *      class's own progressLog ever sees. That's the actual fix for "losing context over
 *      micromanaging details": this layer's re-planning prompt (planNextSteps) only ever reads
 *      step-level summaries, staying focused on strategy instead of drowning in command-level
 *      detail.
 * A step failing does NOT abort the whole plan -- the orchestrator moves on to the next step
 * regardless, logs the failure, and lets the NEXT re-planning round (which sees that failure in
 * its progress log) decide whether to retry, work around it, or give up on the goal entirely.
 *
 * Event-triggered interrupts (EventHookDispatcher.runUrgent) and direct user commands
 * (ResponseHandler's SAY:/DO: path) both still go through TaskRunner.interrupt(), which already
 * pauses and resumes whatever task list is currently running regardless of who submitted it -- so
 * a Micromanager's in-flight step transparently survives "base attacked" or a direct new voice
 * command with no new plumbing needed here; see Micromanager's own doc.
 *
 * MAX_ROUNDS is a real, deliberate safety cap, not an incidental limit -- "beat minecraft" is not
 * going to actually finish in 30 rounds of a 3B local model. It exists so a model that never
 * converges on "done" can't run unattended forever burning LLM calls and real-world bot actions.
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
    private static Micromanager currentMicromanager;
    private static String currentStepText = "";

    private TaskOrchestrator() {}

    public static boolean isActive() {
        return active;
    }

    /** The user's original top-level goal ("Main Quest" for QuestTrackerOverlay) -- empty if nothing is active. */
    public static String goal() {
        return active ? goal : "";
    }

    /** The plain-English step currently being worked on ("side quest") -- empty between steps or if nothing is active. */
    public static String currentStepText() {
        return active ? currentStepText : "";
    }

    /** The ascii-command round whichever step is CURRENTLY executing is on -- UI screens (TaskPlannerScreen) use this to keep their own display in sync. Empty if no step is currently running (between steps, or nothing active). */
    public static List<PlannedTask> currentRoundTasks() {
        return currentMicromanager != null ? currentMicromanager.currentRoundTasks() : List.of();
    }

    public static void start(String goalText, Listener listener) {
        if (active || TaskRunner.shared().isActive() || !ArdorMasterToggle.isEnabled()) return;
        active = true;
        goal = goalText;
        progressLog.clear();
        round = 0;
        uiListener = listener;
        listener.onOrchestrationStatus("Planning initial step(s)...");

        TaskPlanner.planSteps(goal)
                .thenAccept(stepPlan -> Minecraft.getInstance().execute(() -> {
                    if (!stepPlan.say().isBlank()) ResponseHandler.sayAloud(stepPlan.say());
                    runStepRound(stepPlan.steps());
                }))
                .exceptionally(err -> {
                    Minecraft.getInstance().execute(() -> fail("Initial planning failed: " + describeError(err)));
                    return null;
                });
    }

    /**
     * Same self-correcting loop as start(), but for an already-decided ascii plan (TaskPlannerScreen's
     * plain "Run" button, running whatever Send produced -- possibly reordered/edited since) instead
     * of planning fresh steps from a goal string. Treated as a single step handed straight to one
     * Micromanager (skipping ITS initial plan() call too, via startWithTasks) -- still gets that
     * class's self-correcting re-plan loop for free, same as before this two-stage split existed.
     */
    public static void startWithTasks(String goalText, List<PlannedTask> initialTasks, Listener listener) {
        if (active || TaskRunner.shared().isActive() || !ArdorMasterToggle.isEnabled()) return;
        active = true;
        goal = goalText;
        progressLog.clear();
        round = 1;
        uiListener = listener;
        currentStepText = goalText;
        currentMicromanager = new Micromanager(goalText);
        currentMicromanager.startWithTasks(initialTasks, uiListener,
                summary -> Minecraft.getInstance().execute(() -> {
                    active = false;
                    currentMicromanager = null;
                    uiListener.onOrchestrationStatus(summary);
                    StatusIndicator.show(summary);
                }),
                reason -> Minecraft.getInstance().execute(() -> fail(reason)));
    }

    public static void stop() {
        if (!active) return;
        active = false;
        // "I can stop it but it immediately resolves" -- cancel the Micromanager INSTANCE itself,
        // not just this class's own reference to it. Nulling currentMicromanager alone left an
        // already-in-flight async plan/re-plan call free to resolve later and just keep going,
        // ignoring the stop entirely -- see Micromanager.cancel()'s own doc for the full story.
        if (currentMicromanager != null) currentMicromanager.cancel();
        currentMicromanager = null;
        TaskRunner.shared().cancel();
        uiListener.onOrchestrationStatus("Stopped by user.");
    }

    /** Pauses whichever step is currently running -- delegates to the shared TaskRunner, which is the one thing actually driving commands regardless of which Micromanager submitted them. */
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

    private static void runStepRound(List<TaskPlanner.PlanStep> steps) {
        if (!active) return;
        round++;
        if (steps.isEmpty()) {
            // The planner returned goalComplete:false but zero steps -- avoid a silent stall by
            // just asking again rather than treating this as "nothing to do, stop."
            continueStepOrchestration();
            return;
        }
        runNextStep(steps, 0);
    }

    private static void runNextStep(List<TaskPlanner.PlanStep> steps, int index) {
        if (!active) return;
        if (index >= steps.size()) {
            continueStepOrchestration();
            return;
        }
        TaskPlanner.PlanStep step = steps.get(index);
        if (!step.say().isBlank()) ResponseHandler.sayAloud(step.say());
        uiListener.onOrchestrationStatus("Round " + round + ": " + step.doText());
        currentStepText = step.doText();

        currentMicromanager = new Micromanager(step.doText());
        currentMicromanager.start(uiListener,
                summary -> Minecraft.getInstance().execute(() -> {
                    progressLog.add(summary);
                    runNextStep(steps, index + 1);
                }),
                reason -> Minecraft.getInstance().execute(() -> {
                    progressLog.add("FAILED: " + step.doText() + " -- " + reason);
                    runNextStep(steps, index + 1);
                }));
    }

    private static void continueStepOrchestration() {
        if (!active) return;
        currentMicromanager = null;
        if (round >= MAX_ROUNDS) {
            fail("Stopped after " + MAX_ROUNDS + " rounds (safety cap) without the goal being marked complete.");
            return;
        }
        uiListener.onOrchestrationStatus("Checking progress toward: " + goal + " (round " + (round + 1) + ")");
        TaskPlanner.planNextSteps(goal, String.join("\n", progressLog))
                .thenAccept(result -> Minecraft.getInstance().execute(() -> {
                    if (result.goalComplete()) {
                        active = false;
                        uiListener.onOrchestrationStatus("Goal complete: " + goal);
                        StatusIndicator.show("Goal complete: " + goal);
                        return;
                    }
                    progressLog.add("Round " + round + ": planned " + result.steps().size() + " more step(s)");
                    runStepRound(result.steps());
                }))
                .exceptionally(err -> {
                    Minecraft.getInstance().execute(() -> fail("Re-planning failed: " + describeError(err)));
                    return null;
                });
    }

    private static void fail(String message) {
        active = false;
        if (currentMicromanager != null) currentMicromanager.cancel();
        currentMicromanager = null;
        uiListener.onOrchestrationStatus(message);
        StatusIndicator.show(message);
    }

    private static String describeError(Throwable err) {
        Throwable cause = err.getCause() != null ? err.getCause() : err;
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
