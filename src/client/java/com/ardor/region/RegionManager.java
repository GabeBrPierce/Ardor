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
        migrateLegacyAutoDefend();
    }

    /**
     * One-time migration for the removal of RegionProfile.autoDefendEnabled (replaced by the
     * per-region, per-category Aggressiveness setting -- see Region's own doc): any profile whose
     * global region still has the exact old default "attack nearest hostile on damage" binding
     * gets that binding removed and hostileMobs=REACTIVE set on that same region instead,
     * preserving the one behavior the old boolean ever actually turned on. Reuses the exact same
     * "the default command string itself is a reliable signal, no need to read the old boolean
     * field at all" trick this method's predecessor (migrateLegacyCrossProfileDefendBinding, now
     * removed) already relied on -- works even though autoDefendEnabled no longer exists to read.
     */
    private void migrateLegacyAutoDefend() {
        boolean changed = false;
        for (RegionProfile profile : profiles.values()) {
            Region global = profile.regions.get("global");
            if (global == null || !LEGACY_DEFAULT_DEFEND_COMMAND.equals(global.eventTasks.get("OnClientTakesDamage"))) continue;
            global.eventTasks.remove("OnClientTakesDamage");
            if (global.hostileMobs == null) global.hostileMobs = Aggressiveness.REACTIVE;
            changed = true;
        }
        if (changed) save();
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

    /** No longer installed anywhere -- kept only as the exact string migrateLegacyAutoDefend matches against to detect a pre-Aggressiveness config. */
    private static final String LEGACY_DEFAULT_DEFEND_COMMAND = "atk @e[category=hostile,distance=8,sort=nearest] until:dead";

    /**
     * "Defend" from SingleSelectionMode's entity sub-wheel: sets the CURRENT region's setting for
     * whichever category the targeted entity falls into (hostile/passive/player -- see
     * SelectorResolver.categoryOf) to at least REACTIVE, so standing in a specific area and
     * pointing "Defend" at, say, a zombie opts THAT region into fighting back against hostiles
     * without touching anything broader. No-op (returns false) if the entity doesn't fall into any
     * of the three categories, or if that category is already Reactive or Proactive here.
     */
    public boolean bumpAggressivenessForCurrentRegion(String category) {
        Region region = resolveRegion(currentProfileKey(), playerPos());
        Aggressiveness current = switch (category) {
            case "hostile" -> region.hostileMobs;
            case "passive" -> region.passiveMobs;
            case "player" -> region.players;
            default -> null;
        };
        if (current != null && current != Aggressiveness.OFF) return false;
        switch (category) {
            case "hostile" -> region.hostileMobs = Aggressiveness.REACTIVE;
            case "passive" -> region.passiveMobs = Aggressiveness.REACTIVE;
            case "player" -> region.players = Aggressiveness.REACTIVE;
            default -> { return false; }
        }
        save();
        return true;
    }

    /**
     * Resolves one of Region's three Aggressiveness fields by walking region -> parent -> ... ->
     * profile's global -> global profile's global (same "nearest wins" shape resolveEventTask
     * uses, NOT hasFlag's OR-across-the-whole-chain shape -- see Region's own doc for why a 3-way
     * setting needs this instead), falling back to `fallback` only if NOTHING in the entire chain
     * (including both global regions) has an opinion.
     */
    private Aggressiveness resolveAggressiveness(String profileKey, BlockPos pos, java.util.function.Function<Region, Aggressiveness> getter, Aggressiveness fallback) {
        return effectiveAggressiveness(profileKey, resolveRegion(profileKey, pos), getter, fallback);
    }

    /**
     * Same walk resolveAggressiveness does, but starting from a specific Region rather than
     * resolving one from a position -- for UI display of "what would this named region's setting
     * resolve to right now if left on Inherit" (RegionEditScreen), where there's a region to edit
     * but not necessarily a player standing inside it.
     */
    public Aggressiveness effectiveAggressiveness(String profileKey, Region region, java.util.function.Function<Region, Aggressiveness> getter, Aggressiveness fallback) {
        RegionProfile profile = ensureGlobalRegion(profileKey);
        Aggressiveness found = walkForAggressiveness(profile, region, getter);
        if (found != null) return found;

        if (!profileKey.equals(RegionProfile.GLOBAL_PROFILE)) {
            Aggressiveness crossProfile = walkForAggressiveness(globalProfile(), globalProfile().regions.get("global"), getter);
            if (crossProfile != null) return crossProfile;
        }
        return fallback;
    }

    private Aggressiveness walkForAggressiveness(RegionProfile profile, Region region, java.util.function.Function<Region, Aggressiveness> getter) {
        Region current = region;
        int guard = 0;
        while (current != null && guard++ < 64) {
            Aggressiveness value = getter.apply(current);
            if (value != null) return value;
            current = current.parent != null ? profile.regions.get(current.parent) : null;
        }
        return null;
    }

    // Baseline defaults at the very top of the chain, public so RegionEditScreen can show "Inherit
    // (resolves to X)" for a named region via effectiveAggressiveness without duplicating these
    // values. Hostile Mobs and Players default OFF -- a past real incident (the bot auto-attacked a
    // friend's piglin via an always-on-by-default reactive defend reflex) is exactly why both stay
    // opt-in; Players doubly so, since auto-targeting a person is a bigger deal than a mob. Passive
    // Mobs defaults REACTIVE since it's low-stakes (a cow/chicken rarely damages the player at all,
    // so this mostly only matters for the rare provoked-animal case) and matches this feature's own
    // literal spec ("(Default)" on Reactive).
    public static final Aggressiveness PASSIVE_MOBS_DEFAULT = Aggressiveness.REACTIVE;
    public static final Aggressiveness HOSTILE_MOBS_DEFAULT = Aggressiveness.OFF;
    public static final Aggressiveness PLAYERS_DEFAULT = Aggressiveness.OFF;

    public Aggressiveness passiveMobsAggressiveness(String profileKey, BlockPos pos) {
        return resolveAggressiveness(profileKey, pos, r -> r.passiveMobs, PASSIVE_MOBS_DEFAULT);
    }

    public Aggressiveness hostileMobsAggressiveness(String profileKey, BlockPos pos) {
        return resolveAggressiveness(profileKey, pos, r -> r.hostileMobs, HOSTILE_MOBS_DEFAULT);
    }

    public Aggressiveness playersAggressiveness(String profileKey, BlockPos pos) {
        return resolveAggressiveness(profileKey, pos, r -> r.players, PLAYERS_DEFAULT);
    }

    /**
     * "area_1", "area_2", ... -- the next unused auto-generated name in profileKey, for Area
     * Selection's "Set As Region" (AreaSelectionMode.setAsRegion). Just the initial name --
     * RegionEditScreen's own Name field can rename it to something more meaningful afterward.
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

    /**
     * Real bug, confirmed via a standalone reproduction against a live regions.json, not guessed:
     * a region whose `parent` names a region that DOESN'T EXIST (a typo, or one that got renamed/
     * deleted) used to count that as one real hop of depth anyway -- `current = regions.get(bad
     * name)` becomes null, but `depth` had already been incremented before the loop noticed. That
     * inflated depth let a region with a broken parent reference beat an ACTUALLY-more-specific
     * sibling with no parent at all (depth 0) in deepestMatch's "deepest wins" tie-break, even when
     * the broken-parent region was much larger and fully contained the real target. Confirmed live:
     * a region with `"parent": "base"` (no such region existed) was silently winning over two much
     * smaller, correctly-configured child-of-global regions nested inside it, because "base" not
     * resolving was read as depth 1 instead of the dangling reference it actually was. Now only
     * counts a hop once the named parent is confirmed to actually exist.
     */
    private int depthOf(RegionProfile profile, Region r) {
        int depth = 0;
        Region current = r;
        while (current != null && current.parent != null) {
            Region next = profile.regions.get(current.parent);
            if (next == null) break; // dangling parent reference -- not a real hop, stop counting
            current = next;
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
