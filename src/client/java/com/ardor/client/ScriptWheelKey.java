package com.ardor.client;

import com.ardor.game.PathfindingController;
import com.ardor.script.ScriptEngine;
import com.ardor.script.ScriptStore;
import com.ardor.script.ScriptWheelEntry;
import com.ardor.script.ScriptWheelStore;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * "open-wheel-gui (configurable wheel GUI that can execute scripts/macros)" -- a second, separate
 * radial menu from ArdorWheelScreen's main Single Selection/Area Selection/Pick Block wheel,
 * configured via ScriptWheelStore (edited with //ardor wheel add/remove/list, see
 * ScriptWheelCommands) instead of hardcoded. Bound to J by default -- unbound in vanilla,
 * rebindable via Controls if it clashes with another mod (same convention FetchItemsKey already
 * established for its own key).
 */
public final class ScriptWheelKey {

    private static final KeyMapping KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.ardor.scriptwheel", InputConstants.KEY_J, KeyMapping.Category.MISC));

    private ScriptWheelKey() {}

    public static void register() {
        KeybindTicker.add(KEY, ScriptWheelKey::open);
    }

    private static void open() {
        Minecraft client = Minecraft.getInstance();
        if (client.screen == null) {
            client.setScreen(new ArdorWheelScreen(buildOptions()));
        }
    }

    private static List<ArdorWheelScreen.WheelOption> buildOptions() {
        List<ScriptWheelEntry> entries = ScriptWheelStore.load();
        List<ArdorWheelScreen.WheelOption> options = new ArrayList<>();
        for (ScriptWheelEntry entry : entries) {
            options.add(new ArdorWheelScreen.WheelOption(entry.label, () -> run(entry)));
        }
        if (options.isEmpty()) {
            options.add(new ArdorWheelScreen.WheelOption("(no wedges configured -- //ardor wheel add)", () -> {}));
        }
        return options;
    }

    private static void run(ScriptWheelEntry entry) {
        if ("macro".equals(entry.kind)) {
            JsonObject action = new JsonObject();
            action.addProperty("action", "macro");
            action.addProperty("name", entry.name);
            try {
                PathfindingController.dispatch(action);
            } catch (RuntimeException e) {
                StatusIndicator.show("Wheel macro '" + entry.name + "' failed: " + e.getMessage());
            }
            return;
        }
        try {
            String source = ScriptStore.load(entry.name);
            ScriptEngine.run(source, entry.name, error ->
                    Minecraft.getInstance().execute(() -> StatusIndicator.show("Wheel script '" + entry.name + "' failed: " + error)));
        } catch (RuntimeException e) {
            StatusIndicator.show("Wheel script '" + entry.name + "' failed to load: " + e.getMessage());
        }
    }
}
