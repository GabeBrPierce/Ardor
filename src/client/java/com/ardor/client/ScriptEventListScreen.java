package com.ardor.client;

import com.ardor.event.ScriptEventStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Name box + New up top, one row per ScriptEventStore.list() entry below with Edit/Delete -- same
 * shape as WheelListScreen/ScriptListScreen/MacroListScreen. Separate from the older EventConfigScreen
 * (that one binds a fixed Fabric-event catalog to a region-scoped task; this one is a script-defined,
 * interval-polled predicate with its own subscriber list -- two genuinely different mechanisms, kept
 * as two screens rather than merged into one confusing form.
 */
public final class ScriptEventListScreen extends Screen {

    private static final int ROW_TOP = 40;
    private static final int ROW_H = 20;

    private EditBox nameBox;
    private List<String> events = List.of();

    public ScriptEventListScreen() {
        super(Component.literal("Script Events"));
    }

    @Override
    protected void init() {
        rebuildAllWidgets();
    }

    private void rebuildAllWidgets() {
        clearWidgets();

        nameBox = new EditBox(font, 10, 10, 300, 20, Component.literal("Name"));
        nameBox.setHint(Component.literal("Event name"));
        nameBox.setMaxLength(100);
        addRenderableWidget(nameBox);
        setInitialFocus(nameBox);

        FlowLayout flow = new FlowLayout(320, 10, width - 75, 20, 4, 4);
        int[] pos = flow.next(90);
        addRenderableWidget(Button.builder(Component.literal("New"), b -> onNew()).bounds(pos[0], pos[1], 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 65, 10, 55, 20).build());

        events = ScriptEventStore.list();

        int y = ROW_TOP;
        for (String name : events) {
            addRenderableWidget(Button.builder(Component.literal("Edit"), b -> Minecraft.getInstance().setScreen(new ScriptEventEditScreen(name)))
                    .bounds(390, y, 55, ROW_H - 2).build());
            addRenderableWidget(Button.builder(Component.literal("Delete"), b -> {
                ScriptEventStore.delete(name);
                rebuildAllWidgets();
            }).bounds(450, y, 60, ROW_H - 2).build());
            y += ROW_H;
        }
    }

    private void onNew() {
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) return;
        ScriptEventStore.save(new com.ardor.event.ScriptEventDef(name));
        Minecraft.getInstance().setScreen(new ScriptEventEditScreen(name));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xC0101010);
        g.text(font, getTitle().getString(), 10, 1, 0xFFFFFFFF);

        int y = ROW_TOP;
        for (String name : events) {
            g.text(font, name, 10, y + 5, 0xFFFFFFFF);
            y += ROW_H;
        }
        if (events.isEmpty()) {
            g.text(font, "No script events yet -- type a name and press New.", 10, ROW_TOP, 0xFF808080);
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
