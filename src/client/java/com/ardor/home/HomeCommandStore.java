package com.ardor.home;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Persists named HomeCommands to config/ardor-homecommands/<name>.json -- same one-file-per-name shape as StructStore/MacroStore. */
public final class HomeCommandStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private HomeCommandStore() {}

    private static Path dir() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("ardor-homecommands");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return dir;
    }

    public static void save(HomeCommand home) {
        try {
            Files.writeString(dir().resolve(home.name + ".json"), GSON.toJson(home));
        } catch (IOException e) {
            throw new RuntimeException("failed to save home command '" + home.name + "': " + e.getMessage(), e);
        }
    }

    public static HomeCommand load(String name) {
        Path path = dir().resolve(name + ".json");
        if (!Files.exists(path)) throw new IllegalArgumentException("no home command named '" + name + "'");
        try {
            return GSON.fromJson(Files.readString(path), HomeCommand.class);
        } catch (IOException e) {
            throw new RuntimeException("failed to load home command '" + name + "': " + e.getMessage(), e);
        }
    }

    public static List<String> list() {
        try (var stream = Files.list(dir())) {
            return stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - ".json".length()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
