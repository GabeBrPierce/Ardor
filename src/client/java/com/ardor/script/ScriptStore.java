package com.ardor.script;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Persists named user scripts as plain .lua text files under config/ardor-scripts/<name>.lua -- same directory-per-feature convention as StructStore (ardor-structs) and MacroStore, but plain text rather than JSON since the Lua source itself IS the content, meant to be hand-edited directly. */
public final class ScriptStore {

    private ScriptStore() {}

    private static Path dir() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("ardor-scripts");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return dir;
    }

    public static void save(String name, String source) {
        try {
            Files.writeString(dir().resolve(name + ".lua"), source);
        } catch (IOException e) {
            throw new RuntimeException("failed to save script '" + name + "': " + e.getMessage(), e);
        }
    }

    public static String load(String name) {
        Path path = dir().resolve(name + ".lua");
        if (!Files.exists(path)) throw new IllegalArgumentException("no script named '" + name + "'");
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new RuntimeException("failed to load script '" + name + "': " + e.getMessage(), e);
        }
    }

    public static boolean exists(String name) {
        return Files.exists(dir().resolve(name + ".lua"));
    }

    public static void delete(String name) {
        try {
            Files.deleteIfExists(dir().resolve(name + ".lua"));
        } catch (IOException e) {
            throw new RuntimeException("failed to delete script '" + name + "': " + e.getMessage(), e);
        }
    }

    public static List<String> list() {
        try (var stream = Files.list(dir())) {
            return stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".lua"))
                    .map(n -> n.substring(0, n.length() - ".lua".length()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
