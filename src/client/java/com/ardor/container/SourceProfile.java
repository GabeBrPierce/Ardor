package com.ardor.container;

import java.util.LinkedHashMap;
import java.util.Map;

/** Sources bound to a server/world profile (same key shape as region.RegionProfile: "singleplayer", a server address, or "global"), keyed by ContainerSource.id. */
public final class SourceProfile {
    public String key;
    public Map<String, ContainerSource> sources = new LinkedHashMap<>();

    public SourceProfile() {}

    public SourceProfile(String key) {
        this.key = key;
    }
}
