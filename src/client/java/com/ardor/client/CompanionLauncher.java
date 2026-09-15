package com.ardor.client;

import com.ardor.bridge.BridgeServer;
import com.ardor.config.ArdorConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/**
 * "The UI Companion button doesn't launch the companion." It used to just open a browser tab to
 * the companion's web UI URL, silently assuming CompanionDaemon (a separate JVM process, see the
 * Ardor-Companion project) was already running somewhere -- it never actually started anything.
 * This actually launches it (same "check if already up, launch if configured and not" shape
 * LlmServerManager already established for the local llama-server) before opening the browser.
 *
 * ArdorConfig.companionLauncherPath should point at the Gradle `application` plugin's generated
 * launcher script for Ardor-Companion (build/install/ardor-companion/bin/ardor-companion.bat on
 * Windows) -- blank by default, same "no sane machine-independent default" reasoning
 * llmServerExecutable already documents.
 */
public final class CompanionLauncher {

    private static final String COMPANION_UI_URL = "http://127.0.0.1:24748/";
    private static final int STARTUP_WAIT_MS = 1500; // best-effort: give the daemon a moment to bind its web UI port before opening the browser

    private static Process companionProcess;

    private CompanionLauncher() {}

    public static void ensureRunningThenOpenUi() {
        if (BridgeServer.isCompanionConnected()) {
            openBrowser();
            return;
        }
        if (companionProcess != null && companionProcess.isAlive()) {
            openBrowser();
            return;
        }

        ArdorConfig config = ArdorConfig.get();
        if (config.companionLauncherPath.isBlank()) {
            StatusIndicator.show("Companion not running and no launcher path configured (Settings -> Agent & Bridge -> Companion Launcher Path)");
            openBrowser(); // best-effort -- maybe it's running under a setup this mod doesn't know how to launch
            return;
        }
        File launcher = new File(config.companionLauncherPath);
        if (!launcher.isFile()) {
            StatusIndicator.show("Companion launcher not found: " + launcher.getAbsolutePath());
            return;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(launcher.getAbsolutePath())
                    .directory(launcher.getParentFile())
                    .redirectErrorStream(true);
            Path logFile = FabricLoader.getInstance().getConfigDir().resolve("ardor-companion.log");
            pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
            companionProcess = pb.start();
            StatusIndicator.show("Launching companion (pid " + companionProcess.pid() + ")...");
            new Thread(() -> {
                try {
                    Thread.sleep(STARTUP_WAIT_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                openBrowser();
            }, "ardor-companion-launch-wait").start();
        } catch (IOException e) {
            StatusIndicator.show("Failed to launch companion: " + e.getMessage());
        }
    }

    private static void openBrowser() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(COMPANION_UI_URL));
            }
        } catch (Exception e) {
            System.err.println("[ardor] couldn't open companion UI (open " + COMPANION_UI_URL + " manually): " + e);
        }
    }
}
