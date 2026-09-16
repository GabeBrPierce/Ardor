package com.ardor.client;

import com.ardor.script.ScriptStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Name box + New (creates an empty .lua via ScriptStore.save then opens ScriptEditScreen for it)
 * up top, one row per ScriptStore.list() entry below with Edit/Delete -- same shape as
 * ItemSourcesScreen.
 */
public final class ScriptListScreen extends Screen {

    private static final int ROW_H = 20;

    private EditBox nameBox;
    private List<String> scripts = List.of();
    private int rowTop = 40;

    public ScriptListScreen() {
        super(Component.literal("Scripts"));
    }

    @Override
    protected void init() {
        rebuildAllWidgets();
    }

    private void rebuildAllWidgets() {
        clearWidgets();

        nameBox = new EditBox(font, 10, 10, 300, 20, Component.literal("Name"));
        nameBox.setHint(Component.literal("Script name"));
        nameBox.setMaxLength(100);
        addRenderableWidget(nameBox);
        setInitialFocus(nameBox);

        FlowLayout flow = new FlowLayout(320, 10, width - 130, 20, 4, 4);
        int[] pos = flow.next(90);
        addRenderableWidget(Button.builder(Component.literal("New"), b -> onNew()).bounds(pos[0], pos[1], 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Help"), b -> Minecraft.getInstance().setScreen(new ScriptDocsScreen(this)))
                .bounds(width - 120, 10, 55, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 65, 10, 55, 20).build());

        rowTop = Math.max(40, flow.bottom() + 4);
        scripts = ScriptStore.list();

        int y = rowTop;
        for (String name : scripts) {
            addRenderableWidget(Button.builder(Component.literal("Edit"), b -> Minecraft.getInstance().setScreen(new ScriptEditScreen(name)))
                    .bounds(width - 130, y, 55, ROW_H - 2).build());
            addRenderableWidget(Button.builder(Component.literal("Delete"), b -> {
                ScriptStore.delete(name);
                rebuildAllWidgets();
            }).bounds(width - 70, y, 60, ROW_H - 2).build());
            y += ROW_H;
        }
    }

    private void onNew() {
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) return;
        ScriptStore.save(name, "");
        Minecraft.getInstance().setScreen(new ScriptEditScreen(name));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xC0101010);
        g.text(font, getTitle().getString(), 10, 1, 0xFFFFFFFF);

        int y = rowTop;
        for (String name : scripts) {
            g.text(font, name, 10, y + 5, 0xFFFFFFFF);
            y += ROW_H;
        }
        if (scripts.isEmpty()) {
            g.text(font, "No scripts yet -- type a name and press New.", 10, rowTop, 0xFF808080);
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
