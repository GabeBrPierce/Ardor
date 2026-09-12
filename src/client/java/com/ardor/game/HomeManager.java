package com.ardor.game;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Persists a single named "home" position per profile (see RegionManager for
 * the profile-keying convention this reuses via currentProfileKey()) --
 * structurally the same Gson-singleton-file pattern as RegionManager, just
 * without the region hierarchy: one BlockPos per profileKey, nothing else.
 */
public final class HomeManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type HOME_MAP_TYPE = new TypeToken<Map<String, BlockPos>>() {}.getType();

    private static HomeManager instance;

    private final Map<String, BlockPos> homes = new LinkedHashMap<>();

    private HomeManager() {
        load();
    }

    public static HomeManager get() {
        if (instance == null) instance = new HomeManager();
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("ardor-home.json");
    }

    private void load() {
        Path p = path();
        if (!Files.exists(p)) return;
        try {
            Map<String, BlockPos> loaded = GSON.fromJson(Files.readString(p), HOME_MAP_TYPE);
            if (loaded != null) homes.putAll(loaded);
        } catch (IOException e) {
            System.err.println("[ardor] home: failed to load " + p + ": " + e);
        }
    }

    public void save() {
        try {
            Files.writeString(path(), GSON.toJson(homes, HOME_MAP_TYPE));
        } catch (IOException e) {
            System.err.println("[ardor] home: failed to save: " + e);
        }
    }

    public void setHome(String profileKey, BlockPos pos) {
        homes.put(profileKey, pos);
        save();
    }

    public Optional<BlockPos> getHome(String profileKey) {
        return Optional.ofNullable(homes.get(profileKey));
    }
}
