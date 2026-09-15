package com.ardor.struct;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persists named .struct files to config/ardor-structs/<name>.struct.json -- same shape as MacroStore. */
public final class StructStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private StructStore() {}

    private static Path dir() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("ardor-structs");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return dir;
    }

    public static void save(StructFile struct) {
        try {
            Files.writeString(dir().resolve(struct.name + ".struct.json"), GSON.toJson(struct));
        } catch (IOException e) {
            throw new RuntimeException("failed to save struct '" + struct.name + "': " + e.getMessage(), e);
        }
    }

    public static StructFile load(String name) {
        Path path = dir().resolve(name + ".struct.json");
        if (!Files.exists(path)) throw new IllegalArgumentException("no struct named '" + name + "'");
        try {
            return GSON.fromJson(Files.readString(path), StructFile.class);
        } catch (IOException e) {
            throw new RuntimeException("failed to load struct '" + name + "': " + e.getMessage(), e);
        }
    }

    public static java.util.List<String> list() {
        try (var stream = Files.list(dir())) {
            return stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".struct.json"))
                    .map(n -> n.substring(0, n.length() - ".struct.json".length()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return java.util.List.of();
        }
    }
}
