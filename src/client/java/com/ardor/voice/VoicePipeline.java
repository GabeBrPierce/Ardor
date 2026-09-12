package com.ardor.voice;

import com.ardor.audio.AudioRecorder;
import com.ardor.client.SingleSelectionMode;
import com.ardor.client.StatusIndicator;
import com.ardor.config.ArdorConfig;
import com.ardor.llm.ChatCompletionClient;
import com.ardor.stt.WhisperTranscriber;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Orchestrates push-to-talk -> STT -> LLM -> ResponseHandler (action dispatch, TTS). */
public final class VoicePipeline {

    private static final String DEFAULT_GROQ_URL = "https://api.groq.com/openai/v1";
    private static final double MIN_RECORDING_SECONDS = 0.3; // shorter than this is a stray tap, not speech

    private final AudioRecorder recorder = new AudioRecorder();
    private boolean active;

    public void startListening() {
        if (active) return;
        active = true;
        recorder.start();
    }

    public void stopListeningAndRespond() {
        if (!active) return;
        active = false;
        Path wav = tempFile("wav");
        recorder.stop(wav);

        // A too-brief tap captures a near-empty WAV, which whisper.cpp either
        // fails on or produces no output for -- caught a real one of these live
        // (44-byte header-only files, no error from whisper itself, just no
        // .txt output, which read as a confusing "exit 0" failure). Skip the
        // pipeline entirely rather than send it and get a cryptic error.
        if (recorder.lastDurationSeconds() < MIN_RECORDING_SECONDS) {
            StatusIndicator.show("Hold V longer to speak");
            return;
        }

        String pingContext = SingleSelectionMode.currentContext();

        WhisperTranscriber.transcribe(wav)
                .thenCompose(transcript -> {
                    ArdorConfig config = ArdorConfig.get();
                    if (DEFAULT_GROQ_URL.equals(config.llmBaseUrl) && config.llmApiKey.isBlank()) {
                        throw new IllegalStateException("llmApiKey not set in config/ardor.json");
                    }
                    String message = pingContext != null ? pingContext + "\n" + transcript : transcript;
                    return new ChatCompletionClient(config.llmBaseUrl, config.llmApiKey, config.llmModel, config.llmReasoningEffort)
                            .complete(ResponseHandler.systemPromptFor(config.llmMode), message);
                })
                .thenAccept(response -> ResponseHandler.handle(response, ArdorConfig.get().llmMode))
                .exceptionally(err -> {
                    System.err.println("[ardor] voice pipeline failed: " + err.getMessage());
                    Minecraft.getInstance().execute(() -> StatusIndicator.show("Error: " + err.getCause().getMessage()));
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
