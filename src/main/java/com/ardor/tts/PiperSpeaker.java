package com.ardor.tts;

import com.ardor.config.ArdorConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Shells out to a Piper TTS binary configured via config/ardor.json --
 * not bundled. CLI flags (--model, --output_file, text on stdin) match
 * Piper's documented convention but haven't been verified against a specific
 * build; check these first if synthesis fails.
 */
public final class PiperSpeaker {

    private PiperSpeaker() {}

    public static CompletableFuture<Path> synthesize(String text, Path outWav) {
        return CompletableFuture.supplyAsync(() -> runPiper(text, outWav));
    }

    private static Path runPiper(String text, Path outWav) {
        ArdorConfig config = ArdorConfig.get();
        if (config.piperBinaryPath.isBlank()) {
            throw new IllegalStateException("piperBinaryPath not set in config/ardor.json");
        }
        ProcessBuilder pb = new ProcessBuilder(
                config.piperBinaryPath,
                "--model", config.piperVoicePath,
                "--output_file", outWav.toString()
        );
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            process.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            process.getInputStream().readAllBytes();
            int exit = process.waitFor();
            if (exit != 0) throw new RuntimeException("Piper failed (exit " + exit + ")");
            return outWav;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to run Piper", e);
        }
    }
}
