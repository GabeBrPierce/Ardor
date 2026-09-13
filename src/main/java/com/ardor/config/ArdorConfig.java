package com.ardor.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ardor.llm.LlmProvider;
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
 * llmProvider picks a preset from LlmProvider (local llama-server, Groq, OpenAI, OpenRouter,
 * Together AI, or a fully custom OpenAI-compatible endpoint). llmBaseUrl/llmModel are left blank by
 * default and take the provider's default when blank (see effectiveBaseUrl()/effectiveModel()) --
 * set them explicitly to override the preset without switching provider. llmApiKey is required for
 * every provider except LOCAL. llmMode/llmReasoningEffort still apply on top: set llmMode to
 * "say_do" for a frozen general-purpose model (vs. "single_command" for training/'s fine-tuned
 * protocol), and llmReasoningEffort if the target is a reasoning model (gpt-oss-* needs "low" or a
 * tight max_tokens budget can return empty content).
 */
public final class ArdorConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ArdorConfig instance;

    public String llmProvider = LlmProvider.LOCAL.name();
    public String llmBaseUrl = ""; // blank = use llmProvider's default; see effectiveBaseUrl()
    public String llmApiKey = "";
    public String llmModel = ""; // blank = use llmProvider's default; see effectiveModel()
    public String llmMode = "single_command"; // "say_do" (frozen cloud model) | "single_command" (training/'s fine-tuned protocol)
    public String llmReasoningEffort = ""; // only needed for cloud reasoning models like gpt-oss-*; blank for the local model
    public String wakeWord = "buddy";

    // Auto-launches a local llama-server on client start so LOCAL-provider users don't have to run
    // one by hand -- see LlmServerManager. Off by default and with blank paths: this has no sane
    // machine-independent default (it points at wherever you built training/'s model), so it's
    // opt-in per install rather than assuming everyone has a local model set up. Only takes effect
    // when llmProvider is LOCAL.
    public boolean llmAutoStart = false;
    public String llmServerExecutable = "";
    public String llmServerModelPath = "";
    public int llmServerPort = 8081;

    public String effectiveBaseUrl() {
        return llmBaseUrl.isBlank() ? LlmProvider.fromConfigValue(llmProvider).defaultBaseUrl : llmBaseUrl;
    }

    public String effectiveModel() {
        return llmModel.isBlank() ? LlmProvider.fromConfigValue(llmProvider).defaultModel : llmModel;
    }

    public boolean needsApiKey() {
        return LlmProvider.fromConfigValue(llmProvider).needsApiKey;
    }
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
