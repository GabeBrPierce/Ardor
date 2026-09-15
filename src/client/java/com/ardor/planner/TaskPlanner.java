package com.ardor.planner;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ardor.config.ArdorConfig;
import com.ardor.llm.ChatCompletionClient;
import com.ardor.voice.ResponseHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Turns a free-text player goal ("Get 64 oak logs") into an ordered list of
 * PlannedTasks, each carrying the ascii-grammar command(s) that carry it out.
 * Reuses ResponseHandler.VERB_REFERENCE rather than respecifying the grammar,
 * and ChatCompletionClient/config.llm* rather than a separate LLM setup, so
 * this shares whatever backend (Groq or local fine-tune) the voice pipeline
 * is already pointed at.
 *
 * NOT live-tested against a real model this session (built while the user
 * was away -- see TODO.md) -- the JSON-array-only instruction follows the
 * same shape as SAY_DO_SYSTEM_PROMPT, which needed real iteration against
 * live output before it was reliable; this prompt has had no such pass yet.
 * extractJsonArray() defends against the most likely failure (markdown
 * fences / leading prose around the array) but a genuinely malformed or
 * wrong-shaped response will still throw, surfaced via TaskPlannerScreen.
 */
public final class TaskPlanner {

    public static final String PLANNING_SYSTEM_PROMPT = """
            You are Ardor's task planner. The player describes a goal in plain English.
            Break it into an ordered list of tasks, and each task into one or more commands in
            the compact ascii grammar below. Prefer one command that covers a whole repeated
            goal over many repeated commands -- mine takes an optional n:<count> flag, so
            "Get 64 oak logs" needs only "mine oak_log n:64", not 64 separate mine commands.
            You do NOT know the player's numeric coordinates -- never invent one; omit
            near:/at:-style flags entirely unless a coordinate was actually given to you.
            """ + ResponseHandler.VERB_REFERENCE + """
            Respond with ONLY a JSON array, no prose, no markdown code fences, in this shape:
            [{"description": "<short human-readable task description>", "commands": ["<ascii command>", ...]}, ...]
            If the goal is already one simple step, return a single-element array.

            Example -- "Get 64 oak logs":
            [{"description": "Mine 64 oak logs", "commands": ["mine oak_log n:64"]}]

            Example -- "Kill the nearest zombie, then follow me":
            [{"description": "Kill the nearest zombie", "commands": ["atk @e[type=zombie,limit=1,sort=nearest] until:dead"]},
             {"description": "Follow the player", "commands": ["flw @p"]}]
            """;

    /**
     * "The AI should produce some kind of achievable goal with every task,
     * and can keep piping commands to the client until that goal is
     * complete" -- the continuation prompt for TaskOrchestrator's loop.
     * Given the original goal plus a log of what's happened so far, the
     * model decides whether it's done (goalComplete) or what to do next.
     */
    public static final String ORCHESTRATION_SYSTEM_PROMPT = """
            You are Ardor's autonomous task orchestrator, continuing work on a goal across
            multiple rounds. You'll be given the original goal and a log of what's been done so
            far -- including FAILED lines (a command that didn't work, and why) and RESULT lines
            (what a `query` command found out, e.g. the time of day). Use these to self-correct:
            if the last round failed (couldn't find a block/entity, couldn't reach it, etc.), don't
            just repeat the same command -- change approach. For example, if attacking a spider
            failed because none were found nearby, consider checking the time of day (`query time`)
            and waiting for night, or exploring to find a cave, or digging down to one with `shaft
            spiral:y` if it's underground and out of reach. Decide: is the goal now fully achieved?
            If yes, respond with goalComplete:true and an empty tasks array. If not, decide the
            next concrete, achievable task(s) that make real progress toward it, using the same
            ascii command grammar as before. Keep each round's tasks small and concrete -- you'll
            be asked again once they're done.
            """ + ResponseHandler.VERB_REFERENCE + """
            Respond with ONLY a JSON object, no prose, no markdown code fences, in this shape:
            {"goalComplete": true|false, "tasks": [{"description": "...", "commands": ["..."]}, ...]}
            """;

    public record OrchestrationResult(boolean goalComplete, List<PlannedTask> tasks) {}

    // ------------------------------------------------------- staged plan/micromanager split
    //
    // "Develop a plan (a bunch of small achievable steps). Another specially trained AI works out
    // the details (turns into commands the mod understands)." plan()/planNext() above ALREADY do
    // exactly what a micromanager needs -- goal text in, ascii commands out, with FAILED/RESULT
    // self-correction -- they just used to be called with the user's whole raw request as "goal".
    // Now they're called with ONE step's plain-English doText instead (see Micromanager), and the
    // methods below own the layer ABOVE that: decomposing a goal into steps that stay in plain
    // English, never touching ascii grammar at all, so the planner model doesn't need to know the
    // command grammar and the two roles can be entirely different models (see ArdorConfig's
    // planner* fields).

    public record PlanStep(String say, String doText) {}
    public record StepPlan(String say, List<PlanStep> steps) {}
    public record StepOrchestrationResult(boolean goalComplete, List<PlanStep> steps) {}

    public static final String STEP_PLANNING_SYSTEM_PROMPT = """
            You are Ardor's high-level task planner. The player describes a goal in plain English.
            Break it into an ordered list of small, achievable steps. Each step is handed to a
            SEPARATE specialist that turns plain English into actual game commands -- so do NOT use
            any command syntax or grammar here, just describe each step in plain, concrete English
            (e.g. "Collect three stacks of wooden planks of any variant", not "mine oak_log n:64").
            Keep each step small enough to be a single achievable sub-goal. Give each step (and the
            overall reply) a brief in-character spoken remark.
            Respond with ONLY a JSON object, no prose, no markdown code fences, in this shape:
            {"say": "<brief in-character acknowledgment of the overall goal>",
             "steps": [{"say": "<brief in-character remark for this step>", "doText": "<plain-English sub-goal>"}, ...]}

            Example -- "Build me a house":
            {"say": "You got it!",
             "steps": [
               {"say": "First let's make sure we have enough wood.", "doText": "Collect three stacks of wooden planks of any variant"},
               {"say": "We need torches too, otherwise mobs will spawn.", "doText": "Collect torches"},
               {"say": "Now let's put up the walls.", "doText": "Build four walls of a small house using the collected planks"},
               {"say": "Let's light the place up.", "doText": "Place torches around the inside and outside of the house"}
             ]}
            """;

    /**
     * Same self-correcting shape as planNext(), but re-planning STEPS (plain English) instead of
     * ascii commands -- used once the current round of steps is exhausted, given a log of what
     * each step's micromanager reported back (a compact DONE/FAILED summary per step, NOT that
     * micromanager's own internal retry noise -- see Micromanager/TaskOrchestrator).
     */
    public static final String STEP_ORCHESTRATION_SYSTEM_PROMPT = """
            You are Ardor's high-level task planner, continuing work on a goal across multiple
            rounds. You'll be given the original goal and a log of steps completed or failed so far
            (each a one-line summary from the specialist that executed it -- not raw command
            detail). Decide: is the goal now fully achieved? If yes, respond with goalComplete:true
            and an empty steps array. If not, decide the next small, achievable step(s), in plain
            English (no command syntax -- see the planning prompt for why). If a step failed,
            don't just repeat it verbatim -- change approach given why it failed.
            Respond with ONLY a JSON object, no prose, no markdown code fences, in this shape:
            {"goalComplete": true|false, "steps": [{"say": "...", "doText": "..."}, ...]}
            """;

    public static CompletableFuture<StepPlan> planSteps(String goal) {
        ArdorConfig config = ArdorConfig.get();
        ChatCompletionClient client = new ChatCompletionClient(
                config.plannerEffectiveBaseUrl(), config.plannerEffectiveApiKey(), config.plannerEffectiveModel(), config.plannerEffectiveReasoningEffort());
        return client.complete(STEP_PLANNING_SYSTEM_PROMPT, goal).thenApply(TaskPlanner::parseStepPlan);
    }

    public static CompletableFuture<StepOrchestrationResult> planNextSteps(String goal, String progressLog) {
        ArdorConfig config = ArdorConfig.get();
        ChatCompletionClient client = new ChatCompletionClient(
                config.plannerEffectiveBaseUrl(), config.plannerEffectiveApiKey(), config.plannerEffectiveModel(), config.plannerEffectiveReasoningEffort());
        String userMessage = "Original goal: " + goal + "\n\nSteps completed so far:\n"
                + (progressLog.isBlank() ? "(nothing yet)" : progressLog);
        return client.complete(STEP_ORCHESTRATION_SYSTEM_PROMPT, userMessage).thenApply(TaskPlanner::parseStepOrchestration);
    }

    static StepPlan parseStepPlan(String response) {
        JsonObject obj = JsonParser.parseString(extractJsonObject(response)).getAsJsonObject();
        String say = obj.has("say") ? obj.get("say").getAsString() : "";
        List<PlanStep> steps = new ArrayList<>();
        for (var el : obj.getAsJsonArray("steps")) {
            JsonObject s = el.getAsJsonObject();
            steps.add(new PlanStep(s.has("say") ? s.get("say").getAsString() : "", s.get("doText").getAsString()));
        }
        if (steps.isEmpty()) throw new IllegalArgumentException("planner returned zero steps");
        return new StepPlan(say, steps);
    }

    static StepOrchestrationResult parseStepOrchestration(String response) {
        JsonObject obj = JsonParser.parseString(extractJsonObject(response)).getAsJsonObject();
        boolean complete = obj.has("goalComplete") && obj.get("goalComplete").getAsBoolean();
        List<PlanStep> steps = new ArrayList<>();
        if (obj.has("steps")) {
            for (var el : obj.getAsJsonArray("steps")) {
                JsonObject s = el.getAsJsonObject();
                steps.add(new PlanStep(s.has("say") ? s.get("say").getAsString() : "", s.get("doText").getAsString()));
            }
        }
        return new StepOrchestrationResult(complete, steps);
    }

    private TaskPlanner() {}

    public static CompletableFuture<List<PlannedTask>> plan(String goal) {
        ArdorConfig config = ArdorConfig.get();
        ChatCompletionClient client = new ChatCompletionClient(
                config.effectiveBaseUrl(), config.llmApiKey, config.effectiveModel(), config.llmReasoningEffort);
        return client.complete(PLANNING_SYSTEM_PROMPT, goal).thenApply(TaskPlanner::parse);
    }

    public static CompletableFuture<OrchestrationResult> planNext(String goal, String progressLog) {
        ArdorConfig config = ArdorConfig.get();
        ChatCompletionClient client = new ChatCompletionClient(
                config.effectiveBaseUrl(), config.llmApiKey, config.effectiveModel(), config.llmReasoningEffort);
        String userMessage = "Original goal: " + goal + "\n\nProgress so far:\n"
                + (progressLog.isBlank() ? "(nothing yet)" : progressLog);
        return client.complete(ORCHESTRATION_SYSTEM_PROMPT, userMessage).thenApply(TaskPlanner::parseOrchestration);
    }

    static OrchestrationResult parseOrchestration(String response) {
        String json = extractJsonObject(response);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        boolean complete = obj.has("goalComplete") && obj.get("goalComplete").getAsBoolean();
        List<PlannedTask> tasks = new ArrayList<>();
        if (obj.has("tasks")) {
            for (var el : obj.getAsJsonArray("tasks")) {
                JsonObject t = el.getAsJsonObject();
                List<String> commands = new ArrayList<>();
                for (var cmd : t.getAsJsonArray("commands")) commands.add(cmd.getAsString());
                tasks.add(new PlannedTask(t.get("description").getAsString(), commands));
            }
        }
        return new OrchestrationResult(complete, tasks);
    }

    private static String extractJsonObject(String response) {
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("orchestrator response had no JSON object: " + response);
        }
        return response.substring(start, end + 1);
    }

    static List<PlannedTask> parse(String response) {
        String json = extractJsonArray(response);
        JsonArray array = JsonParser.parseString(json).getAsJsonArray();
        List<PlannedTask> tasks = new ArrayList<>();
        for (var el : array) {
            JsonObject obj = el.getAsJsonObject();
            String description = obj.get("description").getAsString();
            List<String> commands = new ArrayList<>();
            for (var cmd : obj.getAsJsonArray("commands")) commands.add(cmd.getAsString());
            tasks.add(new PlannedTask(description, commands));
        }
        if (tasks.isEmpty()) throw new IllegalArgumentException("planner returned zero tasks");
        return tasks;
    }

    /** Models don't reliably skip markdown fences/prose despite instructions not to (established this session with the SAY:/DO: prompt too) -- take the first '[' .. last ']' rather than trusting the whole response is bare JSON. */
    private static String extractJsonArray(String response) {
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("planner response had no JSON array: " + response);
        }
        return response.substring(start, end + 1);
    }
}
