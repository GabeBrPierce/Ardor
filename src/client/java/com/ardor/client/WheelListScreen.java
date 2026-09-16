package com.ardor.client;

import com.ardor.script.ScriptWheelStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Name box + New (creates an empty wheel then opens WheelEditScreen for it) up top, one row per
 * ScriptWheelStore.list() entry below with Show/Edit/Delete -- same shape as ScriptListScreen/
 * MacroListScreen. "default" is what ScriptWheelKey's J bind and WheelManager.show() with no name
 * open; it's not otherwise special-cased here (deleting it just leaves that key with no wedges
 * configured, same as it starting out empty).
 */
public final class WheelListScreen extends Screen {

    private static final int ROW_H = 20;

    private EditBox nameBox;
    private List<String> wheels = List.of();
    private int rowTop = 40;

    public WheelListScreen() {
        super(Component.literal("Wheels"));
    }

    @Override
    protected void init() {
        rebuildAllWidgets();
    }

    private void rebuildAllWidgets() {
        clearWidgets();

        nameBox = new EditBox(font, 10, 10, 300, 20, Component.literal("Name"));
        nameBox.setHint(Component.literal("Wheel name"));
        nameBox.setMaxLength(100);
        addRenderableWidget(nameBox);
        setInitialFocus(nameBox);

        FlowLayout flow = new FlowLayout(320, 10, width - 75, 20, 4, 4);
        int[] pos = flow.next(90);
        addRenderableWidget(Button.builder(Component.literal("New"), b -> onNew()).bounds(pos[0], pos[1], 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 65, 10, 55, 20).build());

        rowTop = Math.max(40, flow.bottom() + 4);
        wheels = ScriptWheelStore.list();

        int y = rowTop;
        for (String name : wheels) {
            addRenderableWidget(Button.builder(Component.literal("Show"), b -> {
                Minecraft.getInstance().setScreen(null);
                ScriptWheelKey.open(name);
            }).bounds(width - 185, y, 55, ROW_H - 2).build());
            addRenderableWidget(Button.builder(Component.literal("Edit"), b -> Minecraft.getInstance().setScreen(new WheelEditScreen(name)))
                    .bounds(width - 125, y, 50, ROW_H - 2).build());
            addRenderableWidget(Button.builder(Component.literal("Delete"), b -> {
                ScriptWheelStore.delete(name);
                rebuildAllWidgets();
            }).bounds(width - 70, y, 60, ROW_H - 2).build());
            y += ROW_H;
        }
    }

    private void onNew() {
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) return;
        ScriptWheelStore.save(name, new java.util.ArrayList<>());
        Minecraft.getInstance().setScreen(new WheelEditScreen(name));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xC0101010);
        g.text(font, getTitle().getString(), 10, 1, 0xFFFFFFFF);

        int y = rowTop;
        for (String name : wheels) {
            g.text(font, name, 10, y + 5, 0xFFFFFFFF);
            y += ROW_H;
        }
        if (wheels.isEmpty()) {
            g.text(font, "No wheels yet -- type a name and press New.", 10, rowTop, 0xFF808080);
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
