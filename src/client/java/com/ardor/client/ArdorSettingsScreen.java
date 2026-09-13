package com.ardor.client;

import com.ardor.config.ArdorConfig;
import com.ardor.llm.LlmProvider;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Cloth Config screen for the ArdorConfig settings file (config/ardor.json) -- replaces hand-
 * editing that JSON. Reached from ArdorConfigScreen (Mod Menu's entry point), not its own keybind:
 * this is genuinely "configuration" in the sense Cloth Config models (flat toggles/fields/lists),
 * unlike the mod's other screens (Task Planner, Fetch Items, Regions, etc.), which are live
 * interactive tools with no Cloth Config equivalent -- see ArdorConfigScreen's javadoc.
 */
public final class ArdorSettingsScreen {

    private ArdorSettingsScreen() {}

    public static Screen create(Screen parent) {
        ArdorConfig cfg = ArdorConfig.get();
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("Ardor Settings"))
                .setSavingRunnable(cfg::save);
        ConfigEntryBuilder eb = builder.entryBuilder();

        ConfigCategory llm = builder.getOrCreateCategory(Component.literal("LLM"));
        llm.addEntry(eb.startStringDropdownMenu(Component.literal("Provider"), cfg.llmProvider)
                .setSelections(Arrays.stream(LlmProvider.values()).map(Enum::name).toList())
                .setTooltip(Component.literal("Picks the default Base URL/Model below when those are left blank. LOCAL = a llama-server you run yourself; the rest are cloud APIs and need an API Key."))
                .setSaveConsumer(v -> cfg.llmProvider = v)
                .build());
        llm.addEntry(eb.startStrField(Component.literal("Base URL"), cfg.llmBaseUrl)
                .setDefaultValue("")
                .setTooltip(Component.literal("OpenAI-compatible base URL (not the full /chat/completions path). Blank = use Provider's default."))
                .setSaveConsumer(v -> cfg.llmBaseUrl = v)
                .build());
        llm.addEntry(eb.startStrField(Component.literal("API Key"), cfg.llmApiKey)
                .setDefaultValue("")
                .setTooltip(Component.literal("Required for every Provider except LOCAL."))
                .setSaveConsumer(v -> cfg.llmApiKey = v)
                .build());
        llm.addEntry(eb.startStrField(Component.literal("Model"), cfg.llmModel)
                .setDefaultValue("")
                .setTooltip(Component.literal("Blank = use Provider's default model."))
                .setSaveConsumer(v -> cfg.llmModel = v)
                .build());
        llm.addEntry(eb.startStringDropdownMenu(Component.literal("Mode"), cfg.llmMode)
                .setSelections(List.of("say_do", "single_command"))
                .setSaveConsumer(v -> cfg.llmMode = v)
                .build());
        llm.addEntry(eb.startStrField(Component.literal("Reasoning Effort"), cfg.llmReasoningEffort)
                .setDefaultValue("")
                .setTooltip(Component.literal("Only needed for cloud reasoning models like gpt-oss-*; blank for the local model."))
                .setSaveConsumer(v -> cfg.llmReasoningEffort = v)
                .build());
        llm.addEntry(eb.startStrField(Component.literal("Wake Word"), cfg.wakeWord)
                .setDefaultValue("buddy")
                .setSaveConsumer(v -> cfg.wakeWord = v)
                .build());

        ConfigCategory localServer = builder.getOrCreateCategory(Component.literal("Local LLM Server"));
        localServer.addEntry(eb.startBooleanToggle(Component.literal("Auto-start"), cfg.llmAutoStart)
                .setDefaultValue(false)
                .setTooltip(Component.literal("Only takes effect when Provider (in LLM) is LOCAL. Launches Server Executable with Model Path on client start."))
                .setSaveConsumer(v -> cfg.llmAutoStart = v)
                .build());
        localServer.addEntry(eb.startStrField(Component.literal("Server Executable"), cfg.llmServerExecutable)
                .setDefaultValue("")
                .setTooltip(Component.literal("Path to your own llama-server.exe. See training/README.md."))
                .setSaveConsumer(v -> cfg.llmServerExecutable = v)
                .build());
        localServer.addEntry(eb.startStrField(Component.literal("Model Path"), cfg.llmServerModelPath)
                .setDefaultValue("")
                .setTooltip(Component.literal("Path to your own GGUF model file."))
                .setSaveConsumer(v -> cfg.llmServerModelPath = v)
                .build());
        localServer.addEntry(eb.startIntField(Component.literal("Port"), cfg.llmServerPort)
                .setDefaultValue(8081)
                .setMin(1).setMax(65535)
                .setSaveConsumer(v -> cfg.llmServerPort = v)
                .build());

        ConfigCategory voice = builder.getOrCreateCategory(Component.literal("Voice"));
        voice.addEntry(eb.startStrField(Component.literal("Whisper Binary Path"), cfg.whisperBinaryPath)
                .setSaveConsumer(v -> cfg.whisperBinaryPath = v)
                .build());
        voice.addEntry(eb.startStrField(Component.literal("Whisper Model Path"), cfg.whisperModelPath)
                .setSaveConsumer(v -> cfg.whisperModelPath = v)
                .build());
        voice.addEntry(eb.startStrField(Component.literal("Piper Binary Path"), cfg.piperBinaryPath)
                .setSaveConsumer(v -> cfg.piperBinaryPath = v)
                .build());
        voice.addEntry(eb.startStrField(Component.literal("Piper Voice Path"), cfg.piperVoicePath)
                .setSaveConsumer(v -> cfg.piperVoicePath = v)
                .build());

        ConfigCategory session = builder.getOrCreateCategory(Component.literal("Session"));
        session.addEntry(eb.startLongField(Component.literal("Max Session Bytes"), cfg.maxSessionBytes)
                .setDefaultValue(5_000_000L)
                .setMin(0L)
                .setSaveConsumer(v -> cfg.maxSessionBytes = v)
                .build());
        session.addEntry(eb.startStrField(Component.literal("Archive Dir"), cfg.archiveDir)
                .setDefaultValue("")
                .setTooltip(Component.literal("Blank = local config-dir subfolder; set to an HDD path for SSD->HDD offload."))
                .setSaveConsumer(v -> cfg.archiveDir = v)
                .build());

        ConfigCategory pathfinding = builder.getOrCreateCategory(Component.literal("Pathfinding"));
        pathfinding.addEntry(eb.startStrList(Component.literal("Breakable Blocks"), new ArrayList<>(cfg.breakableBlocks))
                .setTooltip(Component.literal("Block ids the bot may dig through to reach an otherwise-unreachable mine target."))
                .setSaveConsumer(v -> cfg.breakableBlocks = List.copyOf(v))
                .build());

        ConfigCategory bridge = builder.getOrCreateCategory(Component.literal("Agent & Bridge"));
        bridge.addEntry(eb.startBooleanToggle(Component.literal("Agent Enabled"), cfg.agentEnabled)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.agentEnabled = v)
                .build());
        bridge.addEntry(eb.startIntField(Component.literal("Agent Poll Ticks"), cfg.agentPollTicks)
                .setDefaultValue(5)
                .setMin(1)
                .setSaveConsumer(v -> cfg.agentPollTicks = v)
                .build());
        bridge.addEntry(eb.startBooleanToggle(Component.literal("Bridge Enabled"), cfg.bridgeEnabled)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.bridgeEnabled = v)
                .build());
        bridge.addEntry(eb.startIntField(Component.literal("Bridge Port"), cfg.bridgePort)
                .setDefaultValue(24747)
                .setMin(1).setMax(65535)
                .setSaveConsumer(v -> cfg.bridgePort = v)
                .build());

        return builder.build();
    }
}
