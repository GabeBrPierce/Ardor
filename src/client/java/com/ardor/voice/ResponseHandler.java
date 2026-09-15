package com.ardor.voice;

import com.google.gson.JsonObject;
import com.ardor.audio.AudioPlayer;
import com.ardor.game.ActionDispatcher;
import com.ardor.history.ActionHistory;
import com.ardor.history.ChatHistory;
import com.ardor.ir.AsciiActionCodec;
import com.ardor.planner.PlannedTask;
import com.ardor.planner.TaskRunner;
import com.ardor.tts.PiperSpeaker;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Response parsing for both VoicePipeline (voice-triggered) and ChatListener
 * (chat-triggered), in either of two protocols depending on config.llmMode:
 *
 *   "say_do"          -- lines of "SAY: <text>" / "DO: <ascii command>", for
 *                         a frozen general-purpose model prompted fresh each
 *                         call (Groq etc).
 *   "single_command"  -- the whole reply IS one bare ascii command, no SAY,
 *                         no prefix -- matches training/build_sft_dataset.py's
 *                         SYSTEM_PROMPT exactly, for the fine-tuned local
 *                         model that pipeline produces. No server for that
 *                         model exists yet (see TODO.md), so this path is
 *                         unexercised until one does.
 */
public final class ResponseHandler {

    /**
     * One example per verb (argument order matters, flags are key:value after the required
     * args). @x,y,z is a coordinate; @p/@a/@e/@s/@r[...] is an entity selector -- never mix them
     * up, e.g. near: and at: always take a coordinate, never a selector. Shared between the
     * SAY:/DO: prompt (below) and TaskPlanner's decomposition prompt so the two never drift --
     * this exact wording was iteratively tuned against live Groq calls this session (see
     * TODO.md); don't respecify the grammar from scratch elsewhere.
     */
    public static final String VERB_REFERENCE = """
              go @12,64,-8 range:2
              flw @p dist:5
              mine diamond_ore n:3
              plc oak_log[axis=y] @12,64,-8 face:up
              crf stick*4 table:y
              smt iron_ore fuel:coal
              eq diamond_sword hand
              atk @e[type=zombie,limit=1,sort=nearest] until:dead
              drp diamond_sword*3
              use oak_door with:flint_and_steel
              say "hello!" to:@p
              stop why:mission_complete
              wait n:5
              shaft n:20 spiral:y dump:@12,5,-8
              cmd "f home"
              tadd "Craft a pickaxe" "crf wooden_pickaxe*1 table:y"
              tdel 2
              macro "front door parkour"
              query time
              query search oak_log r:16
              sethome
              home
            """;

    public static final String SAY_DO_SYSTEM_PROMPT = """
            You are Ardor, an AI companion playing Minecraft alongside the player.
            Respond ONLY with lines of these two forms:
              SAY: <what you say out loud, in character, brief>
              DO: <one ascii-grammar action command>
            Use zero or more DO lines and zero or more SAY lines, in any order.
            You do NOT know the player's numeric coordinates. All flags are optional --
            omit near:/at:/searchOrigin-style flags entirely unless you were actually
            given a coordinate; they default to the bot's current position, so you
            never need to invent one.
            """ + VERB_REFERENCE + """
            Example reply:
              SAY: On my way!
              DO: go @p range:2
            """;

    /** Verbatim copy of training/build_sft_dataset.py's SYSTEM_PROMPT -- keep in sync by hand if that changes. */
    public static final String SINGLE_COMMAND_SYSTEM_PROMPT =
            "You control a Minecraft bot. Reply with exactly one command in this "
            + "compact ASCII grammar, nothing else: "
            + "go/flw/mine/plc/crf/smt/eq/atk/drp/use/say/stop, "
            + "e.g. 'go @12,64,-8 range:2', 'mine diamond_ore n:3', 'atk @e[type=zombie,limit=1,sort=nearest] until:dead'.";

    private ResponseHandler() {}

    public static String systemPromptFor(String mode) {
        return "single_command".equals(mode) ? SINGLE_COMMAND_SYSTEM_PROMPT : SAY_DO_SYSTEM_PROMPT;
    }

    /** Runs on the Minecraft main thread -- callers may be on any thread (HTTP callback, etc). */
    public static void handle(String response, String mode) {
        Minecraft.getInstance().execute(() -> {
            if ("single_command".equals(mode)) {
                handleSingleCommand(response.trim());
            } else {
                handleSayDo(response);
            }
        });
    }

    private static void handleSingleCommand(String command) {
        if (command.isEmpty()) return;
        dispatchAction(command);
    }

    /**
     * SAY: lines are spoken immediately, in order. DO: lines are collected and
     * handed to TaskRunner as a queue rather than dispatched one after another
     * in this loop -- a model reply commonly has several DO: lines for one
     * request ("cut down trees" -> two "DO: mine oak_log"), and PathfindingController's
     * executor/breaker are shared singletons with no queue of their own: firing
     * them back-to-back with no wait let the second command's pathTo() silently
     * clobber the first's in-flight callback before it ever finished breaking its
     * block (confirmed live 2026-09-01 -- see PathfindingController.dispatch).
     * TaskRunner already solves exactly this (wait for isBusy() to clear between
     * commands), so DO: lines are queued as one single-command task apiece and run
     * through it, same as the Task Manager UI does with a planned task list.
     */
    private static void handleSayDo(String response) {
        List<PlannedTask> doTasks = new ArrayList<>();
        for (String line : response.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("SAY:")) {
                sayAloud(trimmed.substring(4).trim());
            } else if (trimmed.startsWith("DO:")) {
                String cmd = trimmed.substring(3).trim();
                doTasks.add(new PlannedTask(cmd, List.of(cmd)));
            }
        }
        if (!doTasks.isEmpty()) {
            TaskRunner.shared().interrupt(doTasks, SAY_DO_LISTENER, "llm");
        }
    }

    private static final TaskRunner.Listener SAY_DO_LISTENER = new TaskRunner.Listener() {
        @Override public void onTaskStarted(int taskIndex, PlannedTask task) {}
        @Override public void onCommandStarted(int taskIndex, int commandIndex, String command) {}
        @Override public void onCommandFailed(int taskIndex, int commandIndex, String command, String error) {
            System.err.println("[ardor] action failed: " + command + " -- " + error);
        }
        @Override public void onTaskFinished(int taskIndex) {}
        @Override public void onPlanFinished() {}
    };

    private static void dispatchAction(String cmd) {
        try {
            JsonObject action = AsciiActionCodec.decode(cmd);
            ActionHistory.log(action, "llm");
            ActionDispatcher.execute(action);
        } catch (RuntimeException e) {
            System.err.println("[ardor] action failed: " + cmd + " -- " + e.getMessage());
        }
    }

    /** Public entry point for anything outside the SAY:/DO: parsing loop that wants to speak a line the same way (logged to ChatHistory + synthesized via Piper) -- TaskOrchestrator/Micromanager use this for a planned step's own "say" text. */
    public static void sayAloud(String text) {
        ChatHistory.logBotResponse(text);
        speak(text);
    }

    private static void speak(String text) {
        Path wav = tempFile("tts.wav");
        PiperSpeaker.synthesize(text, wav)
                .thenAccept(AudioPlayer::play)
                .exceptionally(err -> {
                    System.err.println("[ardor] TTS failed: " + err.getMessage());
                    return null;
                });
    }

    private static Path tempFile(String suffix) {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("ardor-tmp");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return dir.resolve(UUID.randomUUID() + "." + suffix);
    }
}
