package com.ardor.enchant;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Persists the recovered EnchantSeedState per world/server profile -- same Gson-singleton-file pattern as HomeManager, keyed the same way (RegionManager.currentProfileKey()). */
public final class EnchantSeedStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, EnchantSeedState>>() {}.getType();

    private static EnchantSeedStore instance;

    private final Map<String, EnchantSeedState> entries = new LinkedHashMap<>();

    private EnchantSeedStore() {
        load();
    }

    public static EnchantSeedStore get() {
        if (instance == null) instance = new EnchantSeedStore();
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("ardor-enchant-seed.json");
    }

    private void load() {
        Path p = path();
        if (!Files.exists(p)) return;
        try {
            Map<String, EnchantSeedState> loaded = GSON.fromJson(Files.readString(p), MAP_TYPE);
            if (loaded != null) entries.putAll(loaded);
        } catch (IOException e) {
            System.err.println("[ardor] enchant-seed: failed to load " + p + ": " + e);
        }
    }

    private void save() {
        try {
            Files.writeString(path(), GSON.toJson(entries, MAP_TYPE));
        } catch (IOException e) {
            System.err.println("[ardor] enchant-seed: failed to save: " + e);
        }
    }

    public Optional<EnchantSeedState> get(String profileKey) {
        return Optional.ofNullable(entries.get(profileKey));
    }

    public void put(String profileKey, EnchantSeedState state) {
        entries.put(profileKey, state);
        save();
    }

    public void remove(String profileKey) {
        entries.remove(profileKey);
        save();
    }
}
