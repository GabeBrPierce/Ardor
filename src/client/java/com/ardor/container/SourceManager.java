package com.ardor.container;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.ardor.region.RegionManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists item-source definitions, profile-keyed exactly the way region.RegionManager keys
 * regions (see its class doc) -- sources shouldn't leak between different servers/worlds, same
 * reasoning regions don't. Reuses RegionManager.currentProfileKey() rather than duplicating that
 * server/singleplayer/global resolution logic a second time.
 */
public final class SourceManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type PROFILE_MAP_TYPE = new TypeToken<Map<String, SourceProfile>>() {}.getType();

    public static final String ENDER_CHEST_ID = "enderchest";

    private static SourceManager instance;

    private final Map<String, SourceProfile> profiles = new LinkedHashMap<>();

    private SourceManager() {
        load();
    }

    public static SourceManager get() {
        if (instance == null) instance = new SourceManager();
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("ardor-container-sources.json");
    }

    private void load() {
        Path p = path();
        if (!Files.exists(p)) return;
        try {
            Map<String, SourceProfile> loaded = GSON.fromJson(Files.readString(p), PROFILE_MAP_TYPE);
            if (loaded != null) profiles.putAll(loaded);
        } catch (IOException e) {
            System.err.println("[ardor] container sources: failed to load " + p + ": " + e);
        }
    }

    public void save() {
        try {
            Files.writeString(path(), GSON.toJson(profiles, PROFILE_MAP_TYPE));
        } catch (IOException e) {
            System.err.println("[ardor] container sources: failed to save: " + e);
        }
    }

    /**
     * Ender chest is a built-in pseudo-source, not position-bound -- "we need to have this
     * understand that this follows the player all through a server." Auto-registered once per
     * profile here rather than requiring the user to Add New it, since there's exactly one real
     * choice to make about it (nothing to name/position/point at).
     */
    private SourceProfile ensureProfile(String profileKey) {
        SourceProfile profile = profiles.computeIfAbsent(profileKey, SourceProfile::new);
        profile.sources.computeIfAbsent(ENDER_CHEST_ID, id -> {
            ContainerSource s = new ContainerSource();
            s.id = ENDER_CHEST_ID;
            s.name = "Ender Chest";
            s.type = SourceType.ENDER_CHEST;
            return s;
        });
        return profile;
    }

    public SourceProfile currentProfile() {
        return ensureProfile(RegionManager.currentProfileKey());
    }

    public ContainerSource add(ContainerSource source) {
        currentProfile().sources.put(source.id, source);
        save();
        return source;
    }

    public void update(ContainerSource source) {
        currentProfile().sources.put(source.id, source);
        save();
    }

    public void remove(String id) {
        if (ENDER_CHEST_ID.equals(id)) throw new IllegalArgumentException("the ender chest source can't be removed");
        currentProfile().sources.remove(id);
        save();
    }

    public void setEnabled(String id, boolean enabled) {
        ContainerSource s = currentProfile().sources.get(id);
        if (s == null) throw new IllegalArgumentException("no such source: " + id);
        s.enabled = enabled;
        save();
    }

    public ContainerSource get(String id) {
        return currentProfile().sources.get(id);
    }

    /** Reverse lookup: the PHYSICAL/CAULDRON source (if any) at pos in the current profile -- read-only, doesn't create anything. Used by SingleSelectionMode's container overlay/edit-hook, which only has a BlockPos from the crosshair hit. SUBCONTAINER sources aren't matched here -- they're not standalone blocks in the world, they live inside a slot of some other source. */
    public ContainerSource findByPosition(BlockPos pos) {
        for (ContainerSource s : currentProfile().sources.values()) {
            if ((s.type == SourceType.PHYSICAL || s.type == SourceType.CAULDRON)
                    && s.x != null && s.x == pos.getX() && s.y == pos.getY() && s.z == pos.getZ()) {
                return s;
            }
        }
        return null;
    }

    /** findByPosition(pos), or registers+returns a new source of the given type there if none exists yet -- SingleSelectionMode's edit-hook uses this so middle-clicking an unopened container still lands on a real, editable source, the same way AutoSourceRecorder's right-click path auto-registers one. A deliberate user action (unlike AutoSourceRecorder's passive auto-indexing), so this doesn't check RegionManager's excludeItemSources flag -- that flag is about NOT auto-collecting, not about blocking something the player explicitly asked for. */
    public ContainerSource findOrCreatePhysicalAt(BlockPos pos, SourceType type) {
        ContainerSource existing = findByPosition(pos);
        if (existing != null) return existing;
        ContainerSource source = new ContainerSource();
        source.type = type;
        source.x = pos.getX();
        source.y = pos.getY();
        source.z = pos.getZ();
        return add(source);
    }
}
