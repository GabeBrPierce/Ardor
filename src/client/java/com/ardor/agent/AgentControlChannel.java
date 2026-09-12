package com.ardor.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ardor.config.ArdorConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * File-based command/response channel for an external agent (a coding
 * assistant driving the bot directly, bypassing voice/LLM entirely) to send
 * commands and read results. Deliberately NOT a network listener -- no
 * socket, no attack surface -- since whoever is driving this already has
 * local filesystem access to the machine the game runs on.
 *
 * Protocol: drop a JSON file into config/ardor-agent/commands/, named
 * anything ending in .json, shaped like {"op": "...", ...op-specific args}.
 * This channel polls that directory every agentPollTicks ticks, moves each
 * command file into commands/processed/ as it's picked up (so a half-written
 * file being polled mid-write isn't double-counted), runs the op (see
 * AgentOps), and writes config/ardor-agent/responses/<same filename>
 * with {"ok": true, "result": {...}} or {"ok": false, "error": "..."}.
 * Every op is bounded to OP_TIMEOUT_SECONDS (60s) -- if it hasn't completed
 * by then, a timeout error response is written instead of the caller
 * waiting on a response file that may never appear (a real gap found live:
 * restartGame closes this process, so if anything after the spawn step ever
 * hung, there'd be nobody left running to write a response at all -- the
 * timeout can't help that specific case, only make hangs in ops that DON'T
 * kill the process visible instead of silent).
 *
 * NOTE on restartGame specifically: an unclaimed command file (still sitting
 * in commands/, never moved to processed/) means the game wasn't running or
 * wasn't ticking when it was dropped -- not a timeout at all, since nothing
 * ever started processing it. This timeout doesn't cover that case; there's
 * nothing inside the mod that can respond if the mod isn't running. A
 * caller waiting on a response file still needs its own timeout/give-up
 * logic for "was this ever claimed."
 *
 * Ops: screenshot, restart (soft -- cancels active path/mine/attack, does
 * NOT touch the game process), getpos, getsurroundings, getchunk, command
 * (raw ascii or IR passthrough to ActionDispatcher).
 */
public final class AgentControlChannel {

    private static final int OP_TIMEOUT_SECONDS = 60;

    private final Path commandsDir;
    private final Path processedDir;
    private final Path responsesDir;
    private int ticksUntilPoll;

    private AgentControlChannel() {
        Path root = FabricLoader.getInstance().getConfigDir().resolve("ardor-agent");
        this.commandsDir = root.resolve("commands");
        this.processedDir = commandsDir.resolve("processed");
        this.responsesDir = root.resolve("responses");
    }

    public static void register() {
        if (!ArdorConfig.get().agentEnabled) return;
        AgentControlChannel channel = new AgentControlChannel();
        try {
            Files.createDirectories(channel.commandsDir);
            Files.createDirectories(channel.processedDir);
            Files.createDirectories(channel.responsesDir);
        } catch (IOException e) {
            System.err.println("[ardor] agent channel: failed to create directories, disabling: " + e);
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(channel::tick);
        System.err.println("[ardor] agent channel listening at " + channel.commandsDir);
    }

    // An uncaught exception here crashes the whole client -- confirmed live,
    // see TODO.md and PushToTalk's tick handler. Never let one escape.
    private void tick(Minecraft client) {
        try {
            tickInner();
        } catch (RuntimeException e) {
            System.err.println("[ardor] agent channel tick failed: " + e);
        }
    }

    private void tickInner() {
        int pollEvery = Math.max(1, ArdorConfig.get().agentPollTicks);
        if (ticksUntilPoll-- > 0) return;
        ticksUntilPoll = pollEvery;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(commandsDir, "*.json")) {
            for (Path file : stream) {
                processOne(file);
            }
        } catch (IOException e) {
            System.err.println("[ardor] agent channel: failed to list commands dir: " + e);
        }
    }

    private void processOne(Path file) {
        String name = file.getFileName().toString();
        JsonObject command;
        try {
            command = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (Exception e) {
            // Could be a file still being written -- leave it for next poll instead of erroring.
            return;
        }

        try {
            Files.move(file, processedDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("[ardor] agent channel: failed to claim " + name + ": " + e);
            return;
        }

        AgentOps.run(command)
                .orTimeout(OP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .thenAccept(result -> writeResponse(name, true, result, null))
                .exceptionally(err -> {
                    writeResponse(name, false, null, describeError(err));
                    return null;
                });
    }

    private void writeResponse(String name, boolean ok, JsonObject result, String error) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", ok);
        if (result != null) response.add("result", result);
        if (error != null) response.addProperty("error", error);
        try {
            Files.writeString(responsesDir.resolve(name), response.toString());
        } catch (IOException e) {
            System.err.println("[ardor] agent channel: failed to write response for " + name + ": " + e);
        }
    }

    private static String describeError(Throwable err) {
        Throwable cause = err.getCause() != null ? err.getCause() : err;
        if (cause instanceof TimeoutException) {
            return "op did not complete within " + OP_TIMEOUT_SECONDS + "s (timed out)";
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
