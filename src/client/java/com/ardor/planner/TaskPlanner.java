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

    private TaskPlanner() {}

    public static CompletableFuture<List<PlannedTask>> plan(String goal) {
        ArdorConfig config = ArdorConfig.get();
        ChatCompletionClient client = new ChatCompletionClient(
                config.llmBaseUrl, config.llmApiKey, config.llmModel, config.llmReasoningEffort);
        return client.complete(PLANNING_SYSTEM_PROMPT, goal).thenApply(TaskPlanner::parse);
    }

    public static CompletableFuture<OrchestrationResult> planNext(String goal, String progressLog) {
        ArdorConfig config = ArdorConfig.get();
        ChatCompletionClient client = new ChatCompletionClient(
                config.llmBaseUrl, config.llmApiKey, config.llmModel, config.llmReasoningEffort);
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
