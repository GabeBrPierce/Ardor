package com.ardor.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lets a script activate/deactivate ANY registered keybind by its translation-key name -- this
 * mod's own, vanilla's, or another mod's (Options.keyMappings already includes every mod's
 * bindings via fabric-key-mapping-api-v1's own OptionsMixin splicing them in, confirmed via javap;
 * no widening needed here). A bare KeyMapping.setDown(true) doesn't survive a screen open/close or
 * the window losing/regaining focus -- both call KeyMapping.setAll()/releaseAll(), which re-reads
 * real GLFW state and clobbers a synthetic hold -- so every currently-activated key is
 * re-asserted every tick instead of set once and forgotten.
 */
public final class KeybindControl {

    private static final Set<String> ACTIVE = new LinkedHashSet<>();
    private static boolean registered;

    private KeybindControl() {}

    public static void register() {
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            for (String name : ACTIVE) {
                KeyMapping key = KeyMapping.get(name);
                if (key != null) key.setDown(true);
            }
        });
    }

    public static boolean activate(String name) {
        KeyMapping key = KeyMapping.get(name);
        if (key == null) return false;
        ACTIVE.add(name);
        key.setDown(true);
        return true;
    }

    public static boolean deactivate(String name) {
        KeyMapping key = KeyMapping.get(name);
        boolean was = ACTIVE.remove(name);
        if (key != null) key.setDown(false);
        return was;
    }

    public static List<String> names() {
        return Arrays.stream(Minecraft.getInstance().options.keyMappings)
                .map(KeyMapping::getName)
                .toList();
    }

    public static List<String> query(String regex) {
        Pattern pattern = Pattern.compile(regex);
        return names().stream().filter(n -> pattern.matcher(n).find()).toList();
    }
}
