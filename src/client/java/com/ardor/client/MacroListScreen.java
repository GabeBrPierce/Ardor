package com.ardor.client;

import com.ardor.game.PathfindingController;
import com.ardor.macro.MacroRecorder;
import com.ardor.macro.MacroStore;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Name box + Record (starts MacroRecorder and closes the screen -- recording means moving around,
 * which a menu can't do) up top, becoming a Stop Recording button while one is in flight; one row
 * per MacroStore.list() entry below with Play/Delete. Same shape as ScriptListScreen/
 * ItemSourcesScreen. MacroStopRecordingKey offers the same Stop without reopening this screen.
 */
public final class MacroListScreen extends Screen {

    private static final int ROW_H = 20;

    private EditBox nameBox;
    private List<String> macros = List.of();
    private int rowTop = 60;

    public MacroListScreen() {
        super(Component.literal("Macros"));
    }

    @Override
    protected void init() {
        rebuildAllWidgets();
    }

    private void rebuildAllWidgets() {
        clearWidgets();

        boolean recording = MacroRecorder.isRecording();

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 65, 10, 55, 20).build());

        if (recording) {
            addRenderableWidget(Button.builder(Component.literal("Stop Recording"), b -> {
                MacroRecorder.stop();
                rebuildAllWidgets();
            }).bounds(10, 10, 150, 20).build());
            rowTop = 60;
        } else {
            nameBox = new EditBox(font, 10, 10, 300, 20, Component.literal("Name"));
            nameBox.setHint(Component.literal("Macro name"));
            nameBox.setMaxLength(100);
            addRenderableWidget(nameBox);
            setInitialFocus(nameBox);

            FlowLayout flow = new FlowLayout(320, 10, width - 75, 20, 4, 4);
            int[] pos = flow.next(90);
            addRenderableWidget(Button.builder(Component.literal("Record"), b -> onRecord()).bounds(pos[0], pos[1], 90, 20).build());
            rowTop = Math.max(40, flow.bottom() + 4);
        }

        macros = MacroStore.list();

        int y = rowTop;
        for (String name : macros) {
            addRenderableWidget(Button.builder(Component.literal("Play"), b -> onPlay(name))
                    .bounds(width - 130, y, 55, ROW_H - 2).build());
            addRenderableWidget(Button.builder(Component.literal("Delete"), b -> {
                MacroStore.delete(name);
                rebuildAllWidgets();
            }).bounds(width - 70, y, 60, ROW_H - 2).build());
            y += ROW_H;
        }
    }

    private void onRecord() {
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) return;
        MacroRecorder.start(name);
        onClose();
    }

    private void onPlay(String name) {
        JsonObject action = new JsonObject();
        action.addProperty("action", "macro");
        action.addProperty("name", name);
        PathfindingController.dispatch(action);
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xC0101010);
        g.text(font, getTitle().getString(), 10, 1, 0xFFFFFFFF);

        if (MacroRecorder.isRecording()) {
            g.text(font, "Recording... move around, then Stop Recording (or press its keybind, unbound by default).", 10, 34, 0xFFFF5555);
        }

        int y = rowTop;
        for (String name : macros) {
            g.text(font, name, 10, y + 5, 0xFFFFFFFF);
            y += ROW_H;
        }
        if (macros.isEmpty()) {
            g.text(font, "No macros yet -- type a name and press Record.", 10, rowTop, 0xFF808080);
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
