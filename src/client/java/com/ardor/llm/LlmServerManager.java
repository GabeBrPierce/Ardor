package com.ardor.llm;

import com.ardor.config.ArdorConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Auto-launches the local fine-tuned model's llama-server on client start,
 * so the mod "ships with" its own LLM instead of requiring the user to
 * manually run a command every session -- see training/README.md for the
 * documented launch shape this mirrors (`llama-server.exe -m
 * training/gguf_final/qwen2.5-3b-instruct.Q4_K_M.gguf --port 8081 -ngl 99`).
 *
 * Deliberately NOT bundled inside the jar or copied into the CurseForge
 * instance directory: the model + CUDA llama.cpp build are ~2.5GB combined
 * (1.8GB GGUF + 669MB of DLLs), and the instance's drive (C:) had only 12GB
 * free when this was built -- copying would have eaten a fifth of that for
 * a duplicate of files that already exist on D: (668GB free). config.llmServer*
 * paths default to that existing D:\ location directly. This is fine for a
 * personal single-machine mod; it would NOT survive being handed to another
 * user or machine as-is -- see TODO.md.
 *
 * Only launches if nothing is already answering on config.llmBaseUrl (a
 * quick synchronous /models probe, acceptable to block on since this runs
 * once during mod init, not per-frame) -- important given how often this
 * project now restarts the whole game for testing (see restartGame): without
 * this check, every relaunch would spawn a duplicate llama-server fighting
 * the previous one for the same port and the same GPU memory.
 *
 * Output is redirected to a log file, never PIPE -- the same lesson learned
 * building restartGame's child-process spawn: an unconsumed pipe on a
 * long-running process can eventually block it.
 */
public final class LlmServerManager {

    private static Process serverProcess;

    private LlmServerManager() {}

    public static void register() {
        ArdorConfig config = ArdorConfig.get();
        if (!config.llmAutoStart) return;
        try {
            maybeStart(config);
        } catch (RuntimeException e) {
            System.err.println("[ardor] llm auto-start failed: " + e);
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> stop());
    }

    private static void maybeStart(ArdorConfig config) {
        if (isServerUp(config.llmBaseUrl, 1500)) {
            System.err.println("[ardor] llm auto-start: something's already answering at " + config.llmBaseUrl + ", not launching a duplicate");
            LlmStatus.markConnected();
            return;
        }

        File exe = new File(config.llmServerExecutable);
        File model = new File(config.llmServerModelPath);
        if (!exe.isFile() || !model.isFile()) {
            System.err.println("[ardor] llm auto-start: executable or model not found ("
                    + exe.getAbsolutePath() + ", " + model.getAbsolutePath() + ") -- skipping, LLM features need a manually-started server or a fixed path in config");
            LlmStatus.markDisconnected();
            return;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    exe.getAbsolutePath(), "-m", model.getAbsolutePath(),
                    "--port", String.valueOf(config.llmServerPort), "-ngl", "99")
                    .directory(exe.getParentFile())
                    .redirectErrorStream(true);
            Path logFile = FabricLoader.getInstance().getConfigDir().resolve("ardor-llm-server.log");
            pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
            serverProcess = pb.start();
            System.err.println("[ardor] llm auto-start: launched llama-server (pid " + serverProcess.pid() + "), log at " + logFile);
            LlmStatus.markStarting();
        } catch (IOException e) {
            System.err.println("[ardor] llm auto-start: failed to launch: " + e);
            LlmStatus.markDisconnected();
        }
    }

    private static void stop() {
        if (serverProcess != null && serverProcess.isAlive()) {
            serverProcess.destroy();
        }
    }

    static boolean isServerUp(String baseUrl, int timeoutMs) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
            String url = (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl) + "/models";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(timeoutMs)).GET().build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 500; // anything short of a server error means something real is listening
        } catch (Exception e) {
            return false;
        }
    }
}
