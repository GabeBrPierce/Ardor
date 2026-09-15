package com.ardor.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ardor.bridge.BridgeServer;
import com.ardor.llm.LlmStatus;
import com.ardor.planner.PlannedTask;
import com.ardor.planner.TaskOrchestrator;
import com.ardor.planner.TaskPlanner;
import com.ardor.planner.TaskRunner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * "Type out tasks and see the commands they produce": a free-text goal goes
 * to TaskPlanner (LLM decomposition into tasks -> ascii commands). Layout:
 * a goal box + Send along the top; the resulting tasks list down the left
 * side (each reorderable with up/down/X, since reordering IS reprioritizing
 * -- TaskRunner always executes plan.get(0) first); the commands for
 * whichever task is currently first render down the right side with a live
 * status color per command as TaskRunner executes them. One TaskRunner
 * instance is static/shared across screen opens so a plan keeps running in
 * the background if the player closes this screen mid-run.
 *
 * FIXED 2026-08-31 (live user report: "B button is semi-transparent. BUT
 * there is nothing there"): the first version never called
 * super.extractRenderState(...), which is what actually triggers widget
 * rendering (EditBox/Button) in this MC version's GuiGraphicsExtractor
 * pipeline -- confirmed missing by inference from the live symptom (the
 * background fill showed, nothing else did), not re-verified against
 * ChatScreen's bytecode a second time. If widgets still don't render after
 * this fix, that assumption is the next thing to check.
 *
 * LLM connection status (top-right, near the Run/Cancel row): reads
 * LlmStatus.current(), a cached result of an async /models probe against
 * config.llmBaseUrl (see llm/LlmStatus.java, llm/LlmServerManager.java --
 * the latter also auto-launches the local model's llama-server on client
 * start). Refreshed on open and every ~2s while this screen is open
 * (tick()), not on every frame, so rendering never blocks on the network.
 */
public final class TaskPlannerScreen extends Screen {

    private enum Status { PENDING, RUNNING, DONE, FAILED }

    private static final int TASK_LIST_TOP_DEFAULT = 86; // leaves room for a "Tasks" / "Commands" header row above the list
    private static final int ROW_H = 18;
    private static final int LINE_HEIGHT = 12;

    private static final TaskRunner RUNNER = TaskRunner.shared();

    private EditBox input;
    private Button runButton;
    private Button cancelButton;
    private Button pauseButton;

    // Static, not instance fields: a fresh TaskPlannerScreen is constructed every time this is
    // opened (from ArdorConfigScreen's "Task Planner" button), so instance fields reset to empty
    // every reopen -- confirmed live ("items don't seem to persist in between me closing the window
    // and reopening"). The plan/statuses/errors/statusLine themselves need to survive that, same
    // reasoning that already applies to RUNNER being TaskRunner.shared() below.
    private static List<PlannedTask> plan = new ArrayList<>();
    private static List<List<Status>> statuses = new ArrayList<>();
    private static final List<String> errors = new ArrayList<>();
    private static String statusLine = "Type a goal and press Send.";
    private static boolean planning;
    private static String lastGoal = "";
    private int tickCounter;

    /** Recomputed each rebuildAllWidgets() from wherever the (now auto-wrapping, see FlowLayout) button row actually ends -- was a fixed constant, which is what let a wrapped row overlap the task list below it. */
    private int taskListTop = TASK_LIST_TOP_DEFAULT;

    // Companion Tasks panel: a SEPARATE queue living in the companion process (CompanionDaemon /
    // TaskQueue), reached over BridgeServer.requestFromCompanion. Genuinely a different task
    // system from RUNNER/plan above -- not merged, just shown side by side. Static for the same
    // reopen-persistence reason as plan/statuses above.
    private record CompanionTask(String id, String description, String status) {}

    private static List<CompanionTask> companionTasks = new ArrayList<>();
    private static String companionStatusLine = "Not refreshed yet.";
    private static boolean companionRefreshing;

    private static final int LLM_REFRESH_INTERVAL_TICKS = 40; // ~2s at 20 tps

    public TaskPlannerScreen() {
        super(Component.literal("Bot Task Manager"));
    }

    @Override
    protected void init() {
        rebuildAllWidgets();
        LlmStatus.refresh();
    }

    @Override
    public void tick() {
        if (++tickCounter >= LLM_REFRESH_INTERVAL_TICKS) {
            tickCounter = 0;
            LlmStatus.refresh();
            // Companion Tasks panel used to only refresh when the player clicked Refresh --
            // confirmed live nothing else ever updated it. Piggybacks on the same ~2s cadence
            // LlmStatus already uses here rather than adding a second counter.
            onCompanionRefresh();
        }
    }

    private void rebuildAllWidgets() {
        String currentText = input != null ? input.getValue() : "";
        clearWidgets();

        input = new EditBox(font, 10, 10, width - 20 - 70, 20, Component.literal("Goal"));
        input.setHint(Component.literal("Goal"));
        input.setMaxLength(500);
        input.setValue(currentText);
        addRenderableWidget(input);
        setInitialFocus(input);

        addRenderableWidget(Button.builder(Component.literal("Send"), b -> onPlan())
                .bounds(width - 65, 10, 55, 20).build());

        // Auto-wrapping instead of a hardcoded left-to-right march of absolute X positions --
        // confirmed live ("buttons on the right side become cluttered and go on top of each
        // other, even fullscreened") that the OLD fixed positions (10/74/138/202/281/345/406,
        // ending ~496) could run past Close's own `width - 65` spot at a large GUI Scale, since
        // Minecraft's UI coordinates are scaled logical pixels, not raw screen pixels -- "full
        // screen" alone doesn't guarantee enough width. Reserve the top-right corner for Close;
        // everything else flows left-to-right and wraps to a new row instead of overlapping it.
        FlowLayout flow = new FlowLayout(10, 36, width - 75, 20, 4, 4);

        int[] pos = flow.next(60);
        runButton = addRenderableWidget(Button.builder(Component.literal("Run"), b -> onRun())
                .bounds(pos[0], pos[1], 60, 20)
                .tooltip(Tooltip.create(Component.literal("Runs the plan currently shown below exactly once (self-correcting on failures), then stops. Send first to produce that plan from a goal.")))
                .build());
        runButton.active = !plan.isEmpty() && !RUNNER.isActive() && !TaskOrchestrator.isActive() && ArdorMasterToggle.isEnabled();

        pos = flow.next(60);
        cancelButton = addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onCancel())
                .bounds(pos[0], pos[1], 60, 20).build());
        cancelButton.active = RUNNER.isActive() || TaskOrchestrator.isActive();

        pos = flow.next(60);
        pauseButton = addRenderableWidget(Button.builder(Component.literal(RUNNER.isPaused() ? "Resume" : "Pause"), b -> onPauseToggle())
                .bounds(pos[0], pos[1], 60, 20).build());
        pauseButton.active = RUNNER.isActive();

        pos = flow.next(75);
        Button autoButton = addRenderableWidget(Button.builder(Component.literal("Auto-Run"), b -> onAutoRun())
                .bounds(pos[0], pos[1], 75, 20)
                .tooltip(Tooltip.create(Component.literal("Takes whatever's typed in the Goal box above (ignores the plan shown below) and plans+runs it AUTONOMOUSLY: keeps re-planning the next step(s) on its own, round after round, until the goal is done or it gives up -- no need to Send first.")))
                .build());
        autoButton.active = !RUNNER.isActive() && !TaskOrchestrator.isActive() && ArdorMasterToggle.isEnabled();

        pos = flow.next(95);
        addRenderableWidget(Button.builder(Component.literal(ArdorMasterToggle.isEnabled() ? "Ardor: ON" : "Ardor: OFF"),
                        b -> { ArdorMasterToggle.toggle(); rebuildAllWidgets(); })
                .bounds(pos[0], pos[1], 95, 20)
                .tooltip(Tooltip.create(Component.literal("Master on/off switch for all of Ardor's autonomous behavior -- task execution, auto-eat/flee/combat/parry/etc, and the companion bridge. Off stops everything currently running immediately and blocks anything new from starting, same as the keybind (default O).")))
                .build());

        pos = flow.next(60);
        addRenderableWidget(Button.builder(Component.literal("Regions"), b -> Minecraft.getInstance().setScreen(new RegionListScreen()))
                .bounds(pos[0], pos[1], 60, 20).build());
        pos = flow.next(55);
        addRenderableWidget(Button.builder(Component.literal("Events"), b -> Minecraft.getInstance().setScreen(new EventConfigScreen()))
                .bounds(pos[0], pos[1], 55, 20).build());

        pos = flow.next(90);
        addRenderableWidget(Button.builder(Component.literal("Companion UI"), b -> openCompanionUi())
                .bounds(pos[0], pos[1], 90, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 65, 36, 55, 20).build());

        taskListTop = Math.max(TASK_LIST_TOP_DEFAULT, flow.bottom() + 4);

        int y = taskListTop;
        for (int t = 0; t < plan.size(); t++) {
            int index = t;
            addRenderableWidget(Button.builder(Component.literal("▲"), b -> moveTask(index, -1))
                    .bounds(10, y, 16, 16).build());
            addRenderableWidget(Button.builder(Component.literal("▼"), b -> moveTask(index, 1))
                    .bounds(28, y, 16, 16).build());
            addRenderableWidget(Button.builder(Component.literal("X"), b -> removeTask(index))
                    .bounds(46, y, 16, 16).build());
            y += ROW_H;
        }

        int ct = companionTop();
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> onCompanionRefresh())
                .bounds(10, ct, 70, 20).build());

        int cy = ct + 24;
        for (CompanionTask t : companionTasks) {
            addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onCompanionCancel(t.id()))
                    .bounds(10, cy, 50, 14).build());

            boolean paused = "PAUSED".equals(t.status());
            boolean terminal = t.status().equals("DONE") || t.status().equals("FAILED") || t.status().equals("CANCELLED");
            Button pauseResume = addRenderableWidget(Button.builder(
                    Component.literal(paused ? "Resume" : "Pause"),
                    b -> { if (paused) onCompanionResume(t.id()); else onCompanionPause(t.id()); })
                    .bounds(64, cy, 54, 14).build());
            pauseResume.active = !terminal;

            cy += ROW_H;
        }
    }

    /** Bottom-anchored so this panel doesn't need to know how tall the local plan/errors above it end up being. */
    private int companionTop() {
        return height - 140;
    }

    private void onPlan() {
        String goal = input.getValue().trim();
        if (goal.isEmpty() || planning) return;
        planning = true;
        statusLine = "Planning...";
        errors.clear();

        TaskPlanner.plan(goal)
                .thenAccept(tasks -> Minecraft.getInstance().execute(() -> {
                    planning = false;
                    lastGoal = goal;
                    plan = new ArrayList<>(tasks);
                    statuses = new ArrayList<>();
                    for (PlannedTask t : tasks) {
                        List<Status> s = new ArrayList<>();
                        for (int i = 0; i < t.commands().size(); i++) s.add(Status.PENDING);
                        statuses.add(s);
                    }
                    statusLine = tasks.size() + " task(s) planned.";
                    input.setValue("");
                    rebuildAllWidgets();
                }))
                .exceptionally(err -> {
                    Minecraft.getInstance().execute(() -> {
                        planning = false;
                        statusLine = "Planning failed: " + describeError(err);
                    });
                    return null;
                });
    }

    private void moveTask(int index, int delta) {
        int newIndex = index + delta;
        if (newIndex < 0 || newIndex >= plan.size()) return;
        List<PlannedTask> newPlan = new ArrayList<>(plan);
        List<List<Status>> newStatuses = new ArrayList<>(statuses);
        Collections.swap(newPlan, index, newIndex);
        Collections.swap(newStatuses, index, newIndex);
        plan = newPlan;
        statuses = newStatuses;
        rebuildAllWidgets();
    }

    private void removeTask(int index) {
        List<PlannedTask> newPlan = new ArrayList<>(plan);
        List<List<Status>> newStatuses = new ArrayList<>(statuses);
        newPlan.remove(index);
        newStatuses.remove(index);
        plan = newPlan;
        statuses = newStatuses;
        rebuildAllWidgets();
    }

    /**
     * "We still don't send the error back to the LLM. It needs to basically
     * keep trying to produce tasks until they are fixed" -- confirmed live:
     * this ran the already-planned `plan` straight through TaskRunner with
     * no feedback loop at all, so a failed command just sat there red with
     * nothing telling the model. Auto-Run already had exactly this loop
     * (TaskOrchestrator, see below); Run now uses the same loop via
     * startWithTasks, seeded with the plan that's already on screen (which
     * may have been reordered/edited since Send) instead of re-planning
     * from scratch.
     */
    private void onRun() {
        if (plan.isEmpty() || RUNNER.isActive() || TaskOrchestrator.isActive()) return;
        runButton.active = false;
        cancelButton.active = true;
        pauseButton.active = true;
        errors.clear();
        statusLine = "Running...";
        TaskOrchestrator.startWithTasks(lastGoal, new ArrayList<>(plan), orchestratorListener());
    }

    /**
     * "The AI should produce some kind of achievable goal with every task,
     * and can keep piping commands to the client until that goal is
     * complete" -- hands the CURRENT input text straight to TaskOrchestrator
     * (skips the separate Send step; auto-run plans its own first round)
     * and keeps the task/command display in sync as each round's task list
     * changes, by rebuilding plan/statuses from TaskOrchestrator.currentRoundTasks()
     * whenever a new round's first task starts.
     */
    private void onAutoRun() {
        String goal = input.getValue().trim();
        if (goal.isEmpty() || RUNNER.isActive() || TaskOrchestrator.isActive()) return;
        input.setValue("");
        lastGoal = goal;
        plan = new ArrayList<>();
        statuses = new ArrayList<>();
        errors.clear();
        statusLine = "Starting autonomous orchestration...";
        rebuildAllWidgets();
        if (pauseButton != null) pauseButton.active = true;

        TaskOrchestrator.start(goal, orchestratorListener());
    }

    /** Shared between onRun (startWithTasks) and onAutoRun (start) -- both drive the same TaskOrchestrator loop, just with a different entry point into round 1. */
    private TaskOrchestrator.Listener orchestratorListener() {
        return new TaskOrchestrator.Listener() {
            @Override
            public void onOrchestrationStatus(String status) {
                statusLine = status;
                if (runButton != null) runButton.active = !TaskOrchestrator.isActive() && !RUNNER.isActive();
                if (cancelButton != null) cancelButton.active = TaskOrchestrator.isActive() || RUNNER.isActive();
                if (pauseButton != null) {
                    pauseButton.active = RUNNER.isActive();
                    pauseButton.setMessage(Component.literal(RUNNER.isPaused() ? "Resume" : "Pause"));
                }
            }

            @Override
            public void onTaskStarted(int taskIndex, PlannedTask task) {
                if (taskIndex == 0) {
                    plan = new ArrayList<>(TaskOrchestrator.currentRoundTasks());
                    statuses = new ArrayList<>();
                    for (PlannedTask t : plan) {
                        List<Status> s = new ArrayList<>();
                        for (int i = 0; i < t.commands().size(); i++) s.add(Status.PENDING);
                        statuses.add(s);
                    }
                    rebuildAllWidgets();
                }
                statusLine = "Task " + (taskIndex + 1) + "/" + plan.size() + ": " + task.description();
            }

            @Override
            public void onCommandStarted(int taskIndex, int commandIndex, String command) {
                if (taskIndex < statuses.size()) statuses.get(taskIndex).set(commandIndex, Status.RUNNING);
            }

            @Override
            public void onCommandFailed(int taskIndex, int commandIndex, String command, String error) {
                if (taskIndex < statuses.size()) statuses.get(taskIndex).set(commandIndex, Status.FAILED);
                errors.add(command + " -- " + error);
            }

            @Override
            public void onTaskFinished(int taskIndex) {
                if (taskIndex >= statuses.size()) return;
                for (int i = 0; i < statuses.get(taskIndex).size(); i++) {
                    if (statuses.get(taskIndex).get(i) == Status.RUNNING) statuses.get(taskIndex).set(i, Status.DONE);
                }
            }

            // Fires once per ROUND, not once for the whole orchestration -- onOrchestrationStatus
            // is what reports true completion (goal complete, cap hit, cancelled, or a planning
            // error), and already flips runButton/cancelButton there. Re-enabling Run here would
            // let it be pressed again mid-orchestration, between rounds.
            @Override
            public void onPlanFinished() {}
        };
    }

    private void onCancel() {
        RUNNER.cancel();
        TaskOrchestrator.stop();
        statusLine = "Cancelled.";
        runButton.active = true;
        cancelButton.active = false;
        pauseButton.active = false;
        pauseButton.setMessage(Component.literal("Pause"));
    }

    /**
     * Toggles the shared TaskRunner's pause state directly -- not routed only through
     * TaskOrchestrator, since an event-driven interrupt (EventHookDispatcher) can also be
     * driving TaskRunner.shared() outside of an orchestration round, and this button needs to
     * affect "whatever's actually running" either way. TaskOrchestrator.pause()/resume() is
     * additionally called when it's active, purely so its own status text (round number, plan
     * progression) reflects the pause too.
     */
    private void onPauseToggle() {
        if (RUNNER.isPaused()) {
            RUNNER.resume();
            if (TaskOrchestrator.isActive()) TaskOrchestrator.resume();
            else statusLine = "Resumed.";
        } else {
            RUNNER.pause();
            if (TaskOrchestrator.isActive()) TaskOrchestrator.pause();
            else statusLine = "Paused.";
        }
        pauseButton.setMessage(Component.literal(RUNNER.isPaused() ? "Resume" : "Pause"));
    }

    /**
     * requestFromCompanion throws IllegalStateException SYNCHRONOUSLY (not via a failed future)
     * when no companion is connected at all -- see BridgeServer.requestFromCompanion's activeChannel
     * null check. A connected-but-non-responding companion instead times out its future after 10s,
     * which surfaces through the normal .exceptionally path below.
     */
    private void onCompanionRefresh() {
        if (companionRefreshing) return;
        companionRefreshing = true;
        companionStatusLine = "Refreshing...";

        CompletableFuture<JsonObject> future;
        try {
            future = BridgeServer.requestFromCompanion("task.list", new JsonObject());
        } catch (IllegalStateException e) {
            companionRefreshing = false;
            companionStatusLine = "No companion connected.";
            return;
        }

        future.thenAccept(result -> Minecraft.getInstance().execute(() -> {
            companionRefreshing = false;
            List<CompanionTask> tasks = new ArrayList<>();
            JsonArray arr = result.getAsJsonArray("tasks");
            for (var el : arr) {
                JsonObject o = el.getAsJsonObject();
                tasks.add(new CompanionTask(
                        o.get("id").getAsString(),
                        o.get("description").getAsString(),
                        o.get("status").getAsString()));
            }
            companionTasks = tasks;
            companionStatusLine = companionTasks.size() + " companion task(s).";
            rebuildAllWidgets();
        })).exceptionally(err -> {
            Minecraft.getInstance().execute(() -> {
                companionRefreshing = false;
                companionStatusLine = "Companion refresh failed: " + describeError(err);
            });
            return null;
        });
    }

    private void onCompanionCancel(String id) {
        JsonObject args = new JsonObject();
        args.addProperty("id", id);

        CompletableFuture<JsonObject> future;
        try {
            future = BridgeServer.requestFromCompanion("task.cancel", args);
        } catch (IllegalStateException e) {
            companionStatusLine = "No companion connected.";
            return;
        }

        future.thenAccept(result -> Minecraft.getInstance().execute(this::onCompanionRefresh))
                .exceptionally(err -> {
                    Minecraft.getInstance().execute(() -> companionStatusLine = "Cancel failed: " + describeError(err));
                    return null;
                });
    }

    /**
     * Pause/resume on the COMPANION task queue -- see TaskQueue.pause/resume (companion repo).
     * Honest caveat, documented there too: the companion TaskQueue has no execution loop of its
     * own (confirmed by reading CompanionDaemon/TaskQueue end to end -- add/list/cancel only,
     * nothing ever dispatches a queued task's commands over the bridge), so this is a queue-level
     * status flag with the same real-world effect cancel() already has today: none, until a real
     * execution loop is built to read it. Kept for UI/API parity with cancel and so the status is
     * ready to matter the moment such a loop exists. The mod's own local plan (Pause/Resume above)
     * is the one execution path that genuinely halts and resumes in place right now.
     */
    private void onCompanionPause(String id) {
        companionRequestOp("task.pause", id, "Pause failed: ");
    }

    private void onCompanionResume(String id) {
        companionRequestOp("task.resume", id, "Resume failed: ");
    }

    private void companionRequestOp(String what, String id, String failurePrefix) {
        JsonObject args = new JsonObject();
        args.addProperty("id", id);

        CompletableFuture<JsonObject> future;
        try {
            future = BridgeServer.requestFromCompanion(what, args);
        } catch (IllegalStateException e) {
            companionStatusLine = "No companion connected.";
            return;
        }

        future.thenAccept(result -> Minecraft.getInstance().execute(this::onCompanionRefresh))
                .exceptionally(err -> {
                    Minecraft.getInstance().execute(() -> companionStatusLine = failurePrefix + describeError(err));
                    return null;
                });
    }

    /** "The Companion button doesn't launch the companion" -- it used to just open a browser tab assuming CompanionDaemon was already running; see CompanionLauncher for what actually starting it needs. */
    private void openCompanionUi() {
        CompanionLauncher.ensureRunningThenOpenUi();
    }

    private static String describeError(Throwable err) {
        Throwable cause = err.getCause() != null ? err.getCause() : err;
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xC0101010);
        g.text(font, getTitle().getString(), 10, 1, 0xFFFFFFFF);

        g.text(font, statusLine, 10, 60, 0xFFAAAAAA);
        drawLlmStatus(g);

        int leftColRight = width / 2 - 10;
        int rightColX = width / 2 + 10;

        // Flow, left to right: the goal box up top -> Tasks (this column, TaskPlanner's
        // decomposition) -> Commands (right column, the ascii commands the top/current task
        // expands into) -- explicit headers since "it doesn't look like we produce tasks... or
        // commands either" was a live report; the data was always there, just unlabeled (and,
        // separately, invisible -- see the alpha-channel fix noted in this class's header).
        g.text(font, "Tasks (▲▼ reorder, X remove -- top runs first):", 10, taskListTop - LINE_HEIGHT - 4, 0xFFFFFFFF);
        g.text(font, "Commands (from the top task):", rightColX, taskListTop - LINE_HEIGHT - 4, 0xFFFFFFFF);

        int y = taskListTop;
        for (int t = 0; t < plan.size(); t++) {
            int color = t == 0 ? 0xFFFFFF55 : 0xFFFFFFFF;
            g.text(font, plan.get(t).description(), 66, y + 4, color);
            y += ROW_H;
        }

        if (!plan.isEmpty()) {
            PlannedTask current = plan.get(0);
            List<Status> s = statuses.get(0);
            int ry = taskListTop;
            g.text(font, current.description() + ":", rightColX, ry, 0xFFAAAAAA);
            ry += LINE_HEIGHT + 2;
            for (int c = 0; c < current.commands().size(); c++) {
                int color = switch (s.get(c)) {
                    case PENDING -> 0xFF808080;
                    case RUNNING -> 0xFFFFFF55;
                    case DONE -> 0xFF55FF55;
                    case FAILED -> 0xFFFF5555;
                };
                g.text(font, current.commands().get(c), rightColX, ry, color);
                ry += LINE_HEIGHT;
            }
        } else {
            g.text(font, "No tasks yet.", rightColX, taskListTop, 0xFF808080);
        }

        if (!errors.isEmpty()) {
            int ey = Math.max(taskListTop + plan.size() * ROW_H, taskListTop) + 10;
            g.text(font, "Errors:", 10, ey, 0xFFFF5555);
            ey += LINE_HEIGHT;
            for (String e : errors) {
                g.textWithWordWrap(font, FormattedText.of(e), 10, ey, leftColRight, 0xFFFF9999);
                // textWithWordWrap draws as many lines as the text needs (internally: font.split(e,
                // leftColRight) then one text() call per resulting line -- confirmed via
                // disassembly) but only ever advanced by one LINE_HEIGHT here -- confirmed live: a
                // long error (a full BlockPos in the message) wraps to 2-3 lines, and the next
                // error then starts drawing over whatever it hadn't finished yet, producing
                // garbled overlapping text. leftColRight is the exact width value that call uses,
                // so re-deriving the line count with it here matches the real wrap exactly.
                int lines = Math.max(1, font.split(FormattedText.of(e), leftColRight).size());
                ey += LINE_HEIGHT * lines;
            }
        }

        int ct = companionTop();
        g.text(font, "Companion Tasks:", 10, ct - LINE_HEIGHT - 4, 0xFFFFFFFF);
        g.text(font, companionStatusLine, 90, ct + 6, 0xFFAAAAAA);

        int cy = ct + 24;
        for (CompanionTask t : companionTasks) {
            int color = switch (t.status()) {
                case "DONE" -> 0xFF55FF55;
                case "FAILED", "CANCELLED" -> 0xFFFF5555;
                case "RUNNING" -> 0xFFFFFF55;
                case "PAUSED" -> 0xFFFFAA55;
                default -> 0xFFFFFFFF; // PENDING or an unrecognized status
            };
            g.text(font, t.description() + " [" + t.status() + "]", 124, cy + 2, color);
            cy += ROW_H;
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void drawLlmStatus(GuiGraphicsExtractor g) {
        String label;
        int color;
        switch (LlmStatus.current()) {
            case CONNECTED -> { label = "LLM: Connected"; color = 0xFF55FF55; }
            case STARTING -> { label = "LLM: Starting..."; color = 0xFFFFFF55; }
            case DISCONNECTED -> { label = "LLM: Disconnected"; color = 0xFFFF5555; }
            default -> { label = "LLM: Checking..."; color = 0xFF808080; }
        }
        g.text(font, label, width - font.width(label) - 10, 60, color);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
