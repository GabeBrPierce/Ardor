package com.ardor.planner;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * "Another specially trained AI works out the details (turns into commands the mod understands)...
 * failures/updates get sent to the proper layer so the planning AI doesn't lose context over
 * micromanaging details. The micromanager only needs to worry about achieving the CURRENT task."
 *
 * One instance per plan step: takes a single plain-English sub-goal (e.g. "Collect three stacks
 * of wooden planks of any variant"), turns it into ascii commands and runs them (TaskPlanner.plan/
 * planNext -- the SAME self-correcting round loop TaskOrchestrator used to run at the top level,
 * just scoped to one step's goal text instead of the user's whole request), and reports back
 * EXACTLY ONE compact line to its caller when it's done or gives up -- never the round-by-round
 * FAILED/RESULT noise, which stays local to this instance's own progressLog. That's the whole
 * point: the top-level TaskOrchestrator sees "DONE: collected enough planks" or "FAILED: no oak
 * trees found nearby after 3 attempts", not the raw retry log.
 *
 * Unlike the old single static TaskOrchestrator, this is a plain instantiable class -- multiple
 * steps happen one after another (never concurrently, since there's only one shared TaskRunner
 * physically driving the bot), but each gets its own fresh instance and its own scoped progress
 * log, which is what makes the plan tree able to grow/evolve per step without one giant shared
 * state blob.
 *
 * Still drives the ONE shared TaskRunner (TaskRunner.shared()) rather than an instance of its own
 * -- TaskRunner already handles pause/resume and event-triggered interrupt-then-resume generically
 * for whatever list of PlannedTasks is currently running, regardless of who submitted it, so an
 * urgent reactive task (EventHookDispatcher.runUrgent) or a direct user command (ResponseHandler's
 * SAY:/DO: path) transparently pauses and resumes a Micromanager's in-flight step exactly like it
 * already did for a flat plan before this existed -- no new interrupt plumbing needed here.
 */
public final class Micromanager {

    private static final int MAX_ROUNDS = 8; // a single step giving up after 8 rounds is a real gap, not stuck forever silently

    private final String goalText;
    private final List<String> progressLog = new ArrayList<>();
    private int round;
    private TaskRunner.Listener uiListener;
    private Consumer<String> onDone;
    private Consumer<String> onFailed;
    private List<PlannedTask> currentRoundTasks = List.of();
    // "I can stop it but it immediately resolves" -- real bug, confirmed by reading this class:
    // nothing here ever checked whether the instance had been abandoned. PanicStop/TaskOrchestrator.
    // stop() nulled out TaskOrchestrator's OWN reference to the current Micromanager, but an
    // already-in-flight async call (TaskPlanner.plan/planNext, an LLM HTTP round trip) doesn't know
    // or care about that -- the moment its response arrived, continueMicromanaging's callback just
    // called TaskRunner.shared().run(...) again unconditionally, restarting execution as if nothing
    // had happened. cancelled is checked at the top of every entry point AND every async callback
    // before doing anything that would dispatch a new command.
    private volatile boolean cancelled;

    public Micromanager(String goalText) {
        this.goalText = goalText;
    }

    /** Call when this instance is being abandoned (panic stop, TaskOrchestrator.stop()) -- makes every still-pending async callback a no-op instead of silently resuming execution. */
    public void cancel() {
        cancelled = true;
    }

    /** uiListener is a plain pass-through for whatever's showing live progress (the quest-tracker HUD) -- Micromanager doesn't render anything itself. onDone/onFailed each fire exactly once. */
    public void start(TaskRunner.Listener uiListener, Consumer<String> onDone, Consumer<String> onFailed) {
        this.uiListener = uiListener;
        this.onDone = onDone;
        this.onFailed = onFailed;
        TaskPlanner.plan(goalText)
                .thenAccept(tasks -> Minecraft.getInstance().execute(() -> {
                    if (cancelled) return;
                    runRound(tasks);
                }))
                .exceptionally(err -> {
                    Minecraft.getInstance().execute(() -> {
                        if (cancelled) return;
                        fail("couldn't plan '" + goalText + "': " + describeError(err));
                    });
                    return null;
                });
    }

    /** Skips this instance's own initial TaskPlanner.plan() call, running an already-decided task list as round 1 instead -- for TaskOrchestrator.startWithTasks (TaskPlannerScreen's "Run" button, a hand-edited plan), which still gets this class's self-correcting re-plan loop for free on top of it. */
    public void startWithTasks(List<PlannedTask> initialTasks, TaskRunner.Listener uiListener, Consumer<String> onDone, Consumer<String> onFailed) {
        this.uiListener = uiListener;
        this.onDone = onDone;
        this.onFailed = onFailed;
        runRound(initialTasks);
    }

    /** For UI display of whatever ascii-command round this instance is currently executing (TaskOrchestrator.currentRoundTasks(), used by TaskPlannerScreen). */
    public List<PlannedTask> currentRoundTasks() {
        return currentRoundTasks;
    }

    private void runRound(List<PlannedTask> tasks) {
        if (cancelled) return;
        round++;
        currentRoundTasks = tasks;
        if (tasks.isEmpty()) {
            continueMicromanaging();
            return;
        }
        TaskRunner.shared().run(tasks, wrap());
    }

    private TaskRunner.Listener wrap() {
        return new TaskRunner.Listener() {
            @Override public void onTaskStarted(int taskIndex, PlannedTask task) {
                uiListener.onTaskStarted(taskIndex, task);
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
                continueMicromanaging();
            }
        };
    }

    private void continueMicromanaging() {
        if (cancelled) return;
        if (round >= MAX_ROUNDS) {
            fail("gave up on '" + goalText + "' after " + MAX_ROUNDS + " rounds without completing it");
            return;
        }
        TaskPlanner.planNext(goalText, String.join("\n", progressLog))
                .thenAccept(result -> Minecraft.getInstance().execute(() -> {
                    if (cancelled) return;
                    if (result.goalComplete()) {
                        onDone.accept("Done: " + goalText);
                        return;
                    }
                    progressLog.add("Round " + round + ": planned " + result.tasks().size() + " more command(s)");
                    runRound(result.tasks());
                }))
                .exceptionally(err -> {
                    Minecraft.getInstance().execute(() -> {
                        if (cancelled) return;
                        fail("re-planning '" + goalText + "' failed: " + describeError(err));
                    });
                    return null;
                });
    }

    private void fail(String reason) {
        onFailed.accept(reason);
    }

    private static String describeError(Throwable err) {
        Throwable cause = err.getCause() != null ? err.getCause() : err;
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
