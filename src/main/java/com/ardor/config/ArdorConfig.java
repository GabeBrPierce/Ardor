package com.ardor.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Loaded from config/ardor.json (created with defaults on first run).
 * llmApiKey is blank by default -- the voice pipeline no-ops without one
 * rather than crash; see VoicePipeline.
 *
 * llmBaseUrl/llmApiKey/llmModel/llmMode default to the LOCAL fine-tuned
 * model's llama-server (training/README.md's documented shape:
 * http://127.0.0.1:8081/v1, no key needed, "single_command" protocol) as of
 * 2026-09-01 -- LlmServerManager auto-launches that server on client start
 * (see llmAutoStart/llmServer* below), so this is what a fresh install
 * actually gets without any manual setup. Point llmBaseUrl at Groq or any
 * other OpenAI-compatible endpoint instead (see ChatCompletionClient) to go
 * back to a frozen cloud model -- set llmMode to "say_do" and
 * llmReasoningEffort if the target is a reasoning model (gpt-oss-* needed
 * "low" or a tight max_tokens budget could return empty content).
 * llmBaseUrl is a BASE URL, not the full .../chat/completions path --
 * ChatCompletionClient appends the rest.
 */
public final class ArdorConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ArdorConfig instance;

    public String llmBaseUrl = "http://127.0.0.1:8081/v1";
    public String llmApiKey = "";
    public String llmModel = "qwen2.5-3b-instruct";
    public String llmMode = "single_command"; // "say_do" (frozen cloud model) | "single_command" (training/'s fine-tuned protocol)
    public String llmReasoningEffort = ""; // only needed for cloud reasoning models like gpt-oss-*; blank for the local model
    public String wakeWord = "buddy";

    // Auto-launches training/'s llama-server on client start so the mod
    // "ships with" its own LLM -- see LlmServerManager. Paths default to
    // this project's existing D:\ training/ output rather than a copy
    // bundled into the CurseForge instance: the model + CUDA build are
    // ~2.5GB combined, and the instance's drive had only 12GB free when
    // this was added. Personal-machine setup, not portable as-is -- see
    // TODO.md.
    public boolean llmAutoStart = true;
    public String llmServerExecutable = "D:\\source\\repos\\vibe-code\\minecraft-mods\\Ardor\\training\\llama_server\\llama-server.exe";
    public String llmServerModelPath = "D:\\source\\repos\\vibe-code\\minecraft-mods\\Ardor\\training\\gguf_final\\qwen2.5-3b-instruct.Q4_K_M.gguf";
    public int llmServerPort = 8081;
    public String whisperBinaryPath = "";
    public String whisperModelPath = "";
    public String piperBinaryPath = "";
    public String piperVoicePath = "";
    public long maxSessionBytes = 5_000_000L;
    public String archiveDir = ""; // blank = local config-dir subfolder; set to an HDD path for real SSD->HDD offload

    // Blocks the bot is allowed to dig through to reach an otherwise-unreachable
    // mine target (BlockWorldMovement) -- deliberately excludes ores, logs,
    // leaves, and anything player-placed/valuable; only common terrain filler.
    public List<String> breakableBlocks = List.of(
            "minecraft:dirt", "minecraft:grass_block", "minecraft:podzol", "minecraft:mycelium",
            "minecraft:stone", "minecraft:cobblestone", "minecraft:andesite", "minecraft:diorite", "minecraft:granite",
            "minecraft:deepslate", "minecraft:cobbled_deepslate",
            "minecraft:gravel", "minecraft:sand", "minecraft:sandstone", "minecraft:clay",
            "minecraft:netherrack", "minecraft:end_stone"
    );

    // File-based control channel for an external agent (e.g. a coding assistant driving the bot
    // directly, outside the voice pipeline) -- see agent/AgentControlChannel.java. Local
    // filesystem polling only, never a network listener, so there's no remote-control surface.
    // Superseded by the bridge (below) as of the mod/companion split -- kept for now, see TODO.md.
    public boolean agentEnabled = true;
    public int agentPollTicks = 5; // ~0.25s at 20 tps

    // Localhost WebSocket bridge for the Companion App (see bridge/BridgeServer.java) -- the mod's
    // low-level control/query surface for the mod/companion-app split. 127.0.0.1 only, no auth,
    // matching agentEnabled's same "local machine only" posture.
    public boolean bridgeEnabled = true;
    public int bridgePort = 24747;

    public static ArdorConfig get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("ardor.json");
    }

    private static ArdorConfig load() {
        Path path = path();
        if (Files.exists(path)) {
            try {
                return GSON.fromJson(Files.readString(path), ArdorConfig.class);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read " + path, e);
            }
        }
        ArdorConfig defaults = new ArdorConfig();
        defaults.save();
        return defaults;
    }

    public void save() {
        try {
            Files.writeString(path(), GSON.toJson(this));
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + path(), e);
        }
    }
}
