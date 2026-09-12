package com.ardor.region;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists and resolves the region/profile hierarchy: sub-region -> region
 * -> profile's own "global" region -> RegionProfile.GLOBAL_PROFILE's
 * "global" region. A profile is keyed by server address ("singleplayer" for
 * the integrated server, a server's ip for multiplayer, "global" if not
 * connected to any world) -- non-global profiles fall back to the global
 * profile for anything they don't override themselves, per the spec:
 * "profiles... inherits from a global profile and switches on the server URL."
 *
 * Region resolution picks the DEEPEST (most nested) region containing the
 * player's position; among regions at the same position with no clear
 * ancestor relationship (overlapping siblings), the smallest volume wins,
 * matching the intuitive "most specific area" reading of "lowest sub-region."
 */
public final class RegionManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type PROFILE_MAP_TYPE = new TypeToken<Map<String, RegionProfile>>() {}.getType();

    private static RegionManager instance;

    private final Map<String, RegionProfile> profiles = new LinkedHashMap<>();

    private RegionManager() {
        load();
        ensureGlobalRegion(RegionProfile.GLOBAL_PROFILE);
    }

    public static RegionManager get() {
        if (instance == null) instance = new RegionManager();
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("ardor-regions.json");
    }

    private void load() {
        Path p = path();
        if (!Files.exists(p)) return;
        try {
            Map<String, RegionProfile> loaded = GSON.fromJson(Files.readString(p), PROFILE_MAP_TYPE);
            if (loaded != null) profiles.putAll(loaded);
        } catch (IOException e) {
            System.err.println("[ardor] regions: failed to load " + p + ": " + e);
        }
    }

    public void save() {
        try {
            Files.writeString(path(), GSON.toJson(profiles, PROFILE_MAP_TYPE));
        } catch (IOException e) {
            System.err.println("[ardor] regions: failed to save: " + e);
        }
    }

    private RegionProfile ensureGlobalRegion(String profileKey) {
        RegionProfile profile = profiles.computeIfAbsent(profileKey, RegionProfile::new);
        profile.regions.computeIfAbsent("global", n -> {
            Region g = new Region();
            g.name = "global";
            return g;
        });
        return profile;
    }

    /** "singleplayer" for the integrated server, a server's address for multiplayer, "global" if not in a world at all. */
    public static String currentProfileKey() {
        Minecraft client = Minecraft.getInstance();
        if (client.hasSingleplayerServer()) return RegionProfile.SINGLEPLAYER_PROFILE;
        ServerData server = client.getCurrentServer();
        if (server != null && server.ip != null) return server.ip;
        return RegionProfile.GLOBAL_PROFILE;
    }

    public RegionProfile currentProfile() {
        return ensureGlobalRegion(currentProfileKey());
    }

    public RegionProfile globalProfile() {
        return ensureGlobalRegion(RegionProfile.GLOBAL_PROFILE);
    }

    /**
     * "Add an event for the global region where if we are attacked we defend ourselves" -- binds
     * OnClientTakesDamage on the truly global region (RegionProfile.GLOBAL_PROFILE's "global", the
     * last fallback in resolveEventTask's chain, so this applies everywhere regardless of which
     * world/server profile is active) to a raw ascii attack command rather than a natural-language
     * goal, so EventHookDispatcher's ascii-fast-path skips the LLM for it (combat can't afford that
     * latency). category=hostile (see SelectorResolver) targets the nearest hostile mob generically,
     * since damage detection here has no way to identify the actual attacker (see TODO.md).
     *
     * Idempotent and only ever ADDS the binding if the key is absent -- called once at startup so a
     * fresh install gets sensible combat behavior without needing EventConfigScreen setup, but a user
     * who edits or removes this via that screen has their choice respected on every later launch.
     * The one gap: this can't tell "never set" apart from "user deliberately removed it," so a
     * removed binding would come back on the next launch -- acceptable for a just-added default,
     * worth revisiting if that ever surprises someone.
     */
    public void ensureDefaultDefendBinding() {
        Region global = globalProfile().regions.get("global");
        if (global.eventTasks.containsKey("OnClientTakesDamage")) return;
        global.eventTasks.put("OnClientTakesDamage", "atk @e[category=hostile,distance=8,sort=nearest] until:dead");
        save();
    }

    /**
     * "Defend" from SingleSelectionMode's entity sub-wheel -- binds OnClientTakesDamage on the
     * region the player is CURRENTLY standing in, same ascii command ensureDefaultDefendBinding
     * uses for the global default, so a specific area can opt into "fight back" without it
     * applying everywhere. Unlike that method's one-time "only if absent" check, this is a
     * deliberate action each time the wheel option is picked, so it overwrites whatever
     * OnClientTakesDamage binding (if any) the resolved region already had.
     */
    public void bindDefendToCurrentRegion() {
        String profileKey = currentProfileKey();
        Region region = resolveRegion(profileKey, playerPos());
        region.eventTasks.put("OnClientTakesDamage", "atk @e[category=hostile,distance=8,sort=nearest] until:dead");
        save();
    }

    /**
     * "area_1", "area_2", ... -- the next unused auto-generated name in profileKey, for Area
     * Selection's "Set As Region" (AreaSelectionMode.setAsRegion). No rename UI exists anywhere in
     * this codebase yet (RegionListScreen only creates with a typed name; RegionEditScreen edits
     * bounds/parent/flags, not the name itself), so this is what a wheel-created region is called
     * until one does -- see TODO.md.
     */
    public String nextAutoRegionName(String profileKey) {
        RegionProfile profile = ensureGlobalRegion(profileKey);
        int i = 1;
        while (profile.regions.containsKey("area_" + i)) i++;
        return "area_" + i;
    }

    public void setRegion(String profileKey, String name, BlockPos a, BlockPos b) {
        RegionProfile profile = ensureGlobalRegion(profileKey);
        Region existing = profile.regions.get(name);
        if (existing != null && !existing.isGlobal()) {
            existing.setBounds(a, b);
        } else if (existing == null) {
            profile.regions.put(name, new Region(name, null, a, b));
        } else {
            throw new IllegalArgumentException("'" + name + "' is reserved (the implicit global region)");
        }
        save();
    }

    public void deleteRegion(String profileKey, String name) {
        RegionProfile profile = ensureGlobalRegion(profileKey);
        Region existing = profile.regions.get(name);
        if (existing == null) throw new IllegalArgumentException("no such region: '" + name + "'");
        if (existing.isGlobal()) throw new IllegalArgumentException("'" + name + "' is reserved (the implicit global region)");
        profile.regions.remove(name);
        save();
    }

    /** The most specific region containing pos within the given profile, falling back to the global profile's regions if profileKey has no more-specific match, and finally to that profile's own "global" region. */
    public Region resolveRegion(String profileKey, BlockPos pos) {
        RegionProfile profile = ensureGlobalRegion(profileKey);
        Region best = deepestMatch(profile, pos);
        if (best != null && !best.isGlobal()) return best;

        if (!profileKey.equals(RegionProfile.GLOBAL_PROFILE)) {
            Region globalProfileMatch = deepestMatch(globalProfile(), pos);
            if (globalProfileMatch != null && !globalProfileMatch.isGlobal()) return globalProfileMatch;
        }
        return profile.regions.get("global");
    }

    private Region deepestMatch(RegionProfile profile, BlockPos pos) {
        Region best = null;
        int bestDepth = -1;
        for (Region r : profile.regions.values()) {
            if (!r.contains(pos)) continue;
            int depth = depthOf(profile, r);
            if (best == null
                    || depth > bestDepth
                    || (depth == bestDepth && r.volume() < best.volume())) {
                best = r;
                bestDepth = depth;
            }
        }
        return best;
    }

    private int depthOf(RegionProfile profile, Region r) {
        int depth = 0;
        Region current = r;
        while (current != null && current.parent != null) {
            current = profile.regions.get(current.parent);
            depth++;
            if (depth > 64) break; // guard against an accidental parent cycle
        }
        return depth;
    }

    /** Resolves an event id to a task string by walking region -> parent -> ... -> profile's global -> global profile's global, returning the first (most specific) override found, or null if nothing binds this event anywhere in the chain. */
    public String resolveEventTask(String profileKey, BlockPos pos, String eventId) {
        RegionProfile profile = ensureGlobalRegion(profileKey);
        Region region = resolveRegion(profileKey, pos);
        String task = walkForEventTask(profile, region, eventId);
        if (task != null) return task;

        if (!profileKey.equals(RegionProfile.GLOBAL_PROFILE)) {
            return globalProfile().regions.get("global").eventTasks.get(eventId);
        }
        return null;
    }

    private String walkForEventTask(RegionProfile profile, Region region, String eventId) {
        Region current = region;
        int guard = 0;
        while (current != null && guard++ < 64) {
            String task = current.eventTasks.get(eventId);
            if (task != null) return task;
            current = current.parent != null ? profile.regions.get(current.parent) : null;
        }
        return null;
    }

    /** The prefix a chat-phrase binding's key starts with in Region.eventTasks -- see bindChatPhrase/resolveChatPhraseTask. Not a real Fabric or domain event id; the actual phrase is embedded in the key itself, since a single dropdown-selected "OnChatPhrase" entry can't carry a parameter on its own. */
    public static final String CHAT_PHRASE_PREFIX = "OnChatPhrase:";

    public void bindChatPhrase(String profileKey, String regionName, String phrase, String task) {
        RegionProfile profile = ensureGlobalRegion(profileKey);
        Region region = profile.regions.computeIfAbsent(regionName, n -> {
            Region r = new Region();
            r.name = n;
            return r;
        });
        region.eventTasks.put(CHAT_PHRASE_PREFIX + phrase.toLowerCase(), task);
        save();
    }

    /** Case-insensitive substring match against every "OnChatPhrase:<phrase>" binding in the resolved region's inheritance chain (region -> parent -> ... -> profile's global -> global profile's global), same specificity rules as resolveEventTask. Returns the first match found, or null. */
    public String resolveChatPhraseTask(String profileKey, BlockPos pos, String message) {
        String lowerMessage = message.toLowerCase();
        RegionProfile profile = ensureGlobalRegion(profileKey);
        Region region = resolveRegion(profileKey, pos);
        String task = walkForChatPhrase(profile, region, lowerMessage);
        if (task != null) return task;

        if (!profileKey.equals(RegionProfile.GLOBAL_PROFILE)) {
            return findChatPhraseIn(globalProfile().regions.get("global"), lowerMessage);
        }
        return null;
    }

    private String walkForChatPhrase(RegionProfile profile, Region region, String lowerMessage) {
        Region current = region;
        int guard = 0;
        while (current != null && guard++ < 64) {
            String task = findChatPhraseIn(current, lowerMessage);
            if (task != null) return task;
            current = current.parent != null ? profile.regions.get(current.parent) : null;
        }
        return null;
    }

    private String findChatPhraseIn(Region region, String lowerMessage) {
        if (region == null) return null;
        for (Map.Entry<String, String> entry : region.eventTasks.entrySet()) {
            if (!entry.getKey().startsWith(CHAT_PHRASE_PREFIX)) continue;
            String phrase = entry.getKey().substring(CHAT_PHRASE_PREFIX.length());
            if (lowerMessage.contains(phrase)) return entry.getValue();
        }
        return null;
    }

    /**
     * Whether flag is set (true) anywhere in pos's resolved region chain -- region -> parent ->
     * ... -> profile's global -> global profile's global, same profile-resolution shape
     * resolveEventTask uses, but OR-reduced across the WHOLE chain instead of stopping at the
     * first region that has an opinion (see Region's own class doc for why: a plain boolean has
     * no "unset" state the way eventTasks' Map absence does, so "nearest wins" would mean a broad
     * parent flag can't cover its children without re-flagging every one of them).
     */
    public boolean hasFlag(String profileKey, BlockPos pos, java.util.function.Predicate<Region> flag) {
        RegionProfile profile = ensureGlobalRegion(profileKey);
        Region region = resolveRegion(profileKey, pos);
        if (walkForFlag(profile, region, flag)) return true;

        if (!profileKey.equals(RegionProfile.GLOBAL_PROFILE)) {
            return walkForFlag(globalProfile(), globalProfile().regions.get("global"), flag);
        }
        return false;
    }

    private boolean walkForFlag(RegionProfile profile, Region region, java.util.function.Predicate<Region> flag) {
        Region current = region;
        int guard = 0;
        while (current != null && guard++ < 64) {
            if (flag.test(current)) return true;
            current = current.parent != null ? profile.regions.get(current.parent) : null;
        }
        return false;
    }

    public static BlockPos playerPos() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null ? player.blockPosition() : BlockPos.ZERO;
    }
}
