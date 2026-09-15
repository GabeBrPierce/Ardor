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

/** Persists the configurable "open-wheel-gui" -- a single ordered list of ScriptWheelEntry wedges -- as one JSON file (config/ardor-scriptwheel.json), unlike the one-file-per-name stores elsewhere (structs/macros/scripts/home commands) since this is one single wheel's whole configuration, not a named collection of independent items. */
public final class ScriptWheelStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final java.lang.reflect.Type LIST_TYPE = new TypeToken<List<ScriptWheelEntry>>() {}.getType();

    private ScriptWheelStore() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("ardor-scriptwheel.json");
    }

    public static List<ScriptWheelEntry> load() {
        Path path = file();
        if (!Files.exists(path)) return new ArrayList<>();
        try {
            List<ScriptWheelEntry> entries = GSON.fromJson(Files.readString(path), LIST_TYPE);
            return entries != null ? entries : new ArrayList<>();
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    public static void save(List<ScriptWheelEntry> entries) {
        try {
            Files.writeString(file(), GSON.toJson(entries));
        } catch (IOException e) {
            throw new RuntimeException("failed to save script wheel config: " + e.getMessage(), e);
        }
    }
}
