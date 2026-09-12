package com.ardor.history;

import com.google.gson.JsonObject;
import com.ardor.config.ArdorConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;

/**
 * Appends JSONL entries to a session file, rotating to a fresh file and
 * archiving the closed one once it exceeds config.maxSessionBytes. Archive
 * destination is config.archiveDir (point it at an HDD path for real
 * SSD->HDD offload); defaults to a local subfolder since the user's drive
 * layout isn't knowable ahead of time.
 *
 * Files.move (no ATOMIC_MOVE option) is assumed to fall back to copy+delete
 * across filesystems/drives per its documented behavior -- not independently
 * tested cross-drive this session.
 */
public abstract class SessionRecorder {

    private final String prefix;
    private Path currentFile;
    private long currentBytes;

    protected SessionRecorder(String prefix) {
        this.prefix = prefix;
    }

    protected synchronized void append(JsonObject entry) {
        try {
            Path file = currentFile();
            String line = entry + System.lineSeparator();
            Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            currentBytes += line.getBytes(StandardCharsets.UTF_8).length;
            if (currentBytes > ArdorConfig.get().maxSessionBytes) rotate();
        } catch (IOException e) {
            System.err.println("[ardor] failed to write " + prefix + " history: " + e.getMessage());
        }
    }

    private Path currentFile() throws IOException {
        if (currentFile == null) {
            Path dir = stagingDir();
            Files.createDirectories(dir);
            currentFile = dir.resolve(prefix + "-" + System.currentTimeMillis() + ".jsonl");
            currentBytes = 0;
        }
        return currentFile;
    }

    private void rotate() {
        archiveAsync(currentFile);
        currentFile = null;
    }

    private static Path stagingDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("ardor-history");
    }

    private static void archiveAsync(Path closedFile) {
        CompletableFuture.runAsync(() -> {
            try {
                String setting = ArdorConfig.get().archiveDir;
                Path archiveDir = setting.isBlank() ? stagingDir().resolve("archive") : Path.of(setting);
                Files.createDirectories(archiveDir);
                Files.move(closedFile, archiveDir.resolve(closedFile.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                System.err.println("[ardor] failed to archive " + closedFile + ": " + e.getMessage());
            }
        });
    }
}
