package com.ardor.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * "Build into the app UI navigation like Main Menu: Single Player -> My
 * World -> Play -- we should feed the LLM options and it returns a number
 * as what to select." Reads whatever Screen is currently open (any of
 * them, not just Minecraft's own menus -- this works the same for this
 * mod's own screens) and exposes its clickable widgets as a numbered list;
 * `ui.select` re-derives the exact same numbering and dispatches a real
 * click through the widget's own mouseClicked, the same entry point a real
 * mouse click reaches (not a type-specific Button.onPress shortcut, so it
 * works for any clickable widget, not just buttons).
 *
 * MouseButtonEvent/MouseButtonInfo confirmed via javap against the actual
 * bundled minecraft-client.jar this session -- this MC version's input
 * model is MouseButtonEvent(x, y, MouseButtonInfo(button, modifiers)), not
 * the plain (x, y, button) triple older/more familiar vanilla versions use.
 *
 * Operational note, found live: the Minecraft window needs real OS focus
 * for a select() to actually stick. The click itself succeeds either way
 * (mouseClicked returns true, onClick/onPress fires correctly) -- but
 * without window focus, Minecraft's own auto-pause-on-focus-loss behavior
 * immediately reopens the pause screen right after "Back to Game" closes
 * it, making the click look like a no-op from the bridge's side when it
 * actually worked and then got undone a frame later.
 */
final class BridgeUIController {

    private BridgeUIController() {}

    static JsonObject listOptions() {
        Screen screen = Minecraft.getInstance().screen;
        JsonObject result = new JsonObject();
        if (screen == null) {
            result.addProperty("open", false);
            result.add("options", new JsonArray());
            return result;
        }
        result.addProperty("open", true);
        result.addProperty("title", screen.getTitle().getString());

        JsonArray options = new JsonArray();
        List<AbstractWidget> widgets = clickableWidgets(screen);
        for (int i = 0; i < widgets.size(); i++) {
            JsonObject opt = new JsonObject();
            opt.addProperty("index", i);
            opt.addProperty("label", widgets.get(i).getMessage().getString());
            options.add(opt);
        }
        result.add("options", options);
        return result;
    }

    static void select(JsonObject msg) {
        int index = msg.get("index").getAsInt();
        Screen screen = Minecraft.getInstance().screen;
        if (screen == null) throw new IllegalStateException("no screen open");

        List<AbstractWidget> widgets = clickableWidgets(screen);
        if (index < 0 || index >= widgets.size()) {
            throw new IllegalArgumentException("option index " + index + " out of range (0.." + (widgets.size() - 1) + ")");
        }
        AbstractWidget widget = widgets.get(index);
        double cx = widget.getX() + widget.getWidth() / 2.0;
        double cy = widget.getY() + widget.getHeight() / 2.0;
        // doubleClick (optional, default false): list-style widgets -- world selection rows, and
        // presumably any other ObjectSelectionList-based screen -- aren't individually addressable
        // as their own AbstractWidget with a real label (confirmed live: a world list shows as one
        // empty-label entry in ui.list, since the actual rows are a nested entry type this doesn't
        // walk into), but real vanilla lets a double-click on the list jump straight into the
        // selected/hovered row the same way a real player double-clicking a world does. Single click
        // still works for everything already reachable (plain buttons).
        boolean doubleClick = msg.has("doubleClick") && msg.get("doubleClick").getAsBoolean();
        MouseButtonEvent event = new MouseButtonEvent(cx, cy, new MouseButtonInfo(0, 0));
        widget.mouseClicked(event, doubleClick);
    }

    /** Active + visible widgets only, in Screen's own children() order -- listOptions and select must derive the identical numbering from this same method, or a select could hit the wrong widget. */
    private static List<AbstractWidget> clickableWidgets(Screen screen) {
        List<AbstractWidget> result = new ArrayList<>();
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget widget && widget.isActive() && widget.visible) {
                result.add(widget);
            }
        }
        return result;
    }
}
