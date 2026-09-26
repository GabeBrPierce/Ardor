package com.ardor.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Persisted list of user-configured keybinds (KeybindEntry) -- config/ardor-keybinds.json. Replaces
 * the old fixed-slot ScriptKeybinds/MacroKeybinds pools (6 slots each, real vanilla KeyMappings only
 * rebindable via the Controls screen) with an arbitrary-length list whose physical key combo is set
 * directly in KeybindsScreen -- see that class for why a real KeyMapping per bind can't work here
 * ("dynamically create any number of keybinds").
 *
 * One-time migration: if this file doesn't exist yet but either old per-slot name file
 * (ardor-script-keybinds.json / ardor-macro-keybinds.json, plain slot-number -> saved-name maps)
 * does, seeds unbound entries from them so a previously-configured script/macro association isn't
 * silently lost. The physical key itself can't be recovered -- it lived in vanilla's own keybind
 * storage under a KeyMapping this system no longer registers -- so migrated rows start unbound and
 * need one quick press of Rebind. Old files are left on disk untouched (harmless, unused).
 */
public final class KeybindStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type ENTRY_LIST_TYPE = new TypeToken<List<KeybindEntry>>() {}.getType();
    private static final Type LEGACY_SLOT_MAP_TYPE = new TypeToken<Map<Integer, String>>() {}.getType();

    private static List<KeybindEntry> cached;

    private KeybindStore() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("ardor-keybinds.json");
    }

    /** Same in-memory list every call once loaded (not re-read from disk) so DynamicKeybinds' per-entry edge-detection state and KeybindsScreen's in-progress edits share one identity. */
    public static List<KeybindEntry> load() {
        if (cached != null) return cached;
        Path path = file();
        if (Files.exists(path)) {
            try {
                List<KeybindEntry> loaded = GSON.fromJson(Files.readString(path), ENTRY_LIST_TYPE);
                cached = loaded != null ? loaded : new ArrayList<>();
            } catch (IOException e) {
                cached = new ArrayList<>();
            }
        } else {
            cached = migrateLegacy();
        }
        return cached;
    }

    public static void save(List<KeybindEntry> entries) {
        cached = entries;
        try {
            Files.writeString(file(), GSON.toJson(entries));
        } catch (IOException e) {
            throw new RuntimeException("failed to save keybinds: " + e.getMessage(), e);
        }
    }

    private static List<KeybindEntry> migrateLegacy() {
        List<KeybindEntry> entries = new ArrayList<>();
        migrateLegacyFile("ardor-script-keybinds.json", "script", entries);
        migrateLegacyFile("ardor-macro-keybinds.json", "macro", entries);
        if (!entries.isEmpty()) save(entries); // persist the migration immediately so it only ever runs once
        return entries;
    }

    private static void migrateLegacyFile(String filename, String kind, List<KeybindEntry> out) {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(filename);
        if (!Files.exists(path)) return;
        try {
            Map<Integer, String> slots = GSON.fromJson(Files.readString(path), LEGACY_SLOT_MAP_TYPE);
            if (slots == null) return;
            for (String name : slots.values()) {
                KeybindEntry entry = new KeybindEntry();
                entry.kind = kind;
                entry.name = name;
                out.add(entry);
            }
        } catch (IOException e) {
            System.err.println("[ardor] keybind migration: failed to read " + filename + ": " + e);
        }
    }
}
