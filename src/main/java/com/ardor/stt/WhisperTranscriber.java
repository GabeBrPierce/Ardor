package com.ardor.stt;

import com.ardor.config.ArdorConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Shells out to a whisper.cpp binary (whisper-cli or similar) configured via
 * config/ardor.json -- not bundled. CLI flags (-m, -f, -otxt, -of, -nt)
 * match whisper.cpp's documented convention but haven't been verified
 * against a specific build/version; if this fails, check the flags against
 * whatever whisper.cpp build is actually installed first.
 */
public final class WhisperTranscriber {

    private WhisperTranscriber() {}

    public static CompletableFuture<String> transcribe(Path wavFile) {
        return CompletableFuture.supplyAsync(() -> runWhisper(wavFile));
    }

    private static String runWhisper(Path wavFile) {
        ArdorConfig config = ArdorConfig.get();
        if (config.whisperBinaryPath.isBlank()) {
            throw new IllegalStateException("whisperBinaryPath not set in config/ardor.json");
        }
        Path outPrefix = wavFile.resolveSibling(wavFile.getFileName() + ".out");
        ProcessBuilder pb = new ProcessBuilder(
                config.whisperBinaryPath,
                "-m", config.whisperModelPath,
                "-f", wavFile.toString(),
                "-otxt",
                "-of", outPrefix.toString(),
                "-nt"
        );
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exit = process.waitFor();
            Path txtFile = Path.of(outPrefix + ".txt");
            if (exit != 0 || !Files.exists(txtFile)) {
                // whisper.cpp's own output was previously discarded here, which made a
                // real failure (empty/near-empty input WAV, exit 0, but no .txt produced)
                // undiagnosable from the error alone -- keep it now.
                throw new RuntimeException("whisper.cpp failed (exit " + exit + "): " + output.strip());
            }
            return Files.readString(txtFile).trim();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to run whisper.cpp", e);
        }
    }
}
