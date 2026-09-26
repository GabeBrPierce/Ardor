package com.ardor.client;

import java.util.ArrayList;
import java.util.List;

/**
 * One user-configured keybind: a set of physical keyboard key codes (InputConstants.KEY_*) that
 * runs a saved script or macro when pressed together -- one key for a plain bind, several for a
 * chord (multiKey). An empty `keys` list means "not yet bound" (freshly added via KeybindsScreen's
 * Add Keybind, or migrated from the old fixed-slot system with no way to recover its physical key --
 * see KeybindStore).
 */
public final class KeybindEntry {
    public List<Integer> keys = new ArrayList<>();
    public boolean multiKey;
    /** "script" or "macro". */
    public String kind = "script";
    public String name = "";

    /** DynamicKeybinds' own rising-edge tracking -- not persisted (Gson skips transient fields). */
    public transient boolean wasDownLastTick;

    public KeybindEntry() {}
}
