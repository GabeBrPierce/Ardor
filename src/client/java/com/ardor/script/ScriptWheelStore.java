package com.ardor.script;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists any number of NAMED wheels -- each an ordered list of ScriptWheelEntry wedges -- one
 * file per name under config/ardor-wheels/, same one-file-per-item convention ScriptStore/
 * MacroStore already use. Used to be a single unnamed wheel in one flat config/ardor-scriptwheel.json;
 * migrateLegacySingleWheel() imports that file's content as a wheel named "default" the first time
 * this runs against an install that still has it, so nothing configured via the old //ardor wheel
 * add/remove commands is lost.
 */
public final class ScriptWheelStore {

    public static final String DEFAULT_WHEEL = "default";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final java.lang.reflect.Type LIST_TYPE = new TypeToken<List<ScriptWheelEntry>>() {}.getType();

    private ScriptWheelStore() {}

    private static Path dir() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("ardor-wheels");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return dir;
    }

    private static Path legacyFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("ardor-scriptwheel.json");
    }

    static {
        migrateLegacySingleWheel();
    }

    private static void migrateLegacySingleWheel() {
        Path legacy = legacyFile();
        if (!Files.exists(legacy)) return;
        try {
            List<ScriptWheelEntry> entries = GSON.fromJson(Files.readString(legacy), LIST_TYPE);
            if (entries != null) save(DEFAULT_WHEEL, entries);
            Files.delete(legacy);
        } catch (IOException e) {
            System.err.println("[ardor] failed to migrate legacy scriptwheel config: " + e.getMessage());
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

    public static List<ScriptWheelEntry> load(String name) {
        Path path = dir().resolve(name + ".json");
        if (!Files.exists(path)) return new ArrayList<>();
        try {
            List<ScriptWheelEntry> entries = GSON.fromJson(Files.readString(path), LIST_TYPE);
            return entries != null ? entries : new ArrayList<>();
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    public static void save(String name, List<ScriptWheelEntry> entries) {
        try {
            Files.writeString(dir().resolve(name + ".json"), GSON.toJson(entries));
        } catch (IOException e) {
            throw new RuntimeException("failed to save wheel '" + name + "': " + e.getMessage(), e);
        }
    }

    public static void delete(String name) {
        try {
            Files.deleteIfExists(dir().resolve(name + ".json"));
        } catch (IOException e) {
            throw new RuntimeException("failed to delete wheel '" + name + "': " + e.getMessage(), e);
        }
    }
}
