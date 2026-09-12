package com.ardor.region;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A named set of regions bound to a server address ("singleplayer" for the
 * integrated server, or the actual multiplayer server address), or the
 * special GLOBAL_PROFILE key ("global") that every other profile falls back
 * to for anything it doesn't itself override. Always contains a "global"
 * Region as its own root (see RegionManager.ensureGlobalRegion).
 */
public final class RegionProfile {
    public static final String GLOBAL_PROFILE = "global";
    public static final String SINGLEPLAYER_PROFILE = "singleplayer";

    public String key;
    public Map<String, Region> regions = new LinkedHashMap<>();

    public RegionProfile() {}

    public RegionProfile(String key) {
        this.key = key;
    }
}
