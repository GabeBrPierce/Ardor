package com.ardor.macro;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Maps a fixed pool of MacroKeybinds slots to a saved macro name. Mirrors ScriptKeybindStore. */
public final class MacroKeybindStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final java.lang.reflect.Type MAP_TYPE = new TypeToken<Map<Integer, String>>() {}.getType();

    private MacroKeybindStore() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("ardor-macro-keybinds.json");
    }

    public static Map<Integer, String> load() {
        Path path = file();
        if (!Files.exists(path)) return new HashMap<>();
        try {
            Map<Integer, String> map = GSON.fromJson(Files.readString(path), MAP_TYPE);
            return map != null ? map : new HashMap<>();
        } catch (IOException e) {
            return new HashMap<>();
        }
    }

    public static void save(Map<Integer, String> map) {
        try {
            Files.writeString(file(), GSON.toJson(map));
        } catch (IOException e) {
            throw new RuntimeException("failed to save macro keybinds: " + e.getMessage(), e);
        }
    }
}
