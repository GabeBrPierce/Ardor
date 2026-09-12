package com.ardor.macro;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persists named macros to config/ardor-macros/<name>.json. */
public final class MacroStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private MacroStore() {}

    private static Path dir() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("ardor-macros");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return dir;
    }

    public static void save(Macro macro) {
        try {
            Files.writeString(dir().resolve(macro.name + ".json"), GSON.toJson(macro));
        } catch (IOException e) {
            throw new RuntimeException("failed to save macro '" + macro.name + "': " + e.getMessage(), e);
        }
    }

    public static Macro load(String name) {
        Path path = dir().resolve(name + ".json");
        if (!Files.exists(path)) throw new IllegalArgumentException("no macro named '" + name + "'");
        try {
            return GSON.fromJson(Files.readString(path), Macro.class);
        } catch (IOException e) {
            throw new RuntimeException("failed to load macro '" + name + "': " + e.getMessage(), e);
        }
    }

    public static java.util.List<String> list() {
        try (var stream = Files.list(dir())) {
            return stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - ".json".length()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return java.util.List.of();
        }
    }
}
