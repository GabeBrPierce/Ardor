package com.ardor.event;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persists named ScriptEventDefs, one file per name under config/ardor-script-events/. Mirrors MacroStore/ScriptStore. */
public final class ScriptEventStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ScriptEventStore() {}

    private static Path dir() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("ardor-script-events");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return dir;
    }

    public static void save(ScriptEventDef def) {
        try {
            Files.writeString(dir().resolve(def.name + ".json"), GSON.toJson(def));
        } catch (IOException e) {
            throw new RuntimeException("failed to save script event '" + def.name + "': " + e.getMessage(), e);
        }
    }

    public static ScriptEventDef load(String name) {
        Path path = dir().resolve(name + ".json");
        if (!Files.exists(path)) return new ScriptEventDef(name);
        try {
            ScriptEventDef def = GSON.fromJson(Files.readString(path), ScriptEventDef.class);
            return def != null ? def : new ScriptEventDef(name);
        } catch (IOException e) {
            return new ScriptEventDef(name);
        }
    }

    public static void delete(String name) {
        try {
            Files.deleteIfExists(dir().resolve(name + ".json"));
        } catch (IOException e) {
            throw new RuntimeException("failed to delete script event '" + name + "': " + e.getMessage(), e);
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
