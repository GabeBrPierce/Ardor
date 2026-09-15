package com.ardor.script;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps a fixed pool of generic "Ardor Script Slot N" keybinds (ScriptKeybinds -- Fabric/vanilla
 * keybinds are a fixed registered set, not freely creatable at runtime, so a pool of unbound
 * generic slots plus this slot->script mapping is how "bind keys ... to these specific scripts"
 * is done without needing to touch Minecraft's own keybind registration system per script) to a
 * saved script name. One JSON file (config/ardor-script-keybinds.json), same "one small config, not
 * one-file-per-item" shape as ScriptWheelStore.
 */
public final class ScriptKeybindStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final java.lang.reflect.Type MAP_TYPE = new TypeToken<Map<Integer, String>>() {}.getType();

    private ScriptKeybindStore() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("ardor-script-keybinds.json");
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
            throw new RuntimeException("failed to save script keybinds: " + e.getMessage(), e);
        }
    }
}
