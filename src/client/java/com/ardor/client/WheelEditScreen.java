package com.ardor.client;

import com.ardor.macro.MacroStore;
import com.ardor.script.ScriptStore;
import com.ardor.script.ScriptWheelEntry;
import com.ardor.script.ScriptWheelStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Editable wedge list for one named wheel: label EditBox + kind (script/macro) CycleButton + a
 * target-name CycleButton (values depend on the row's own kind, so changing kind rebuilds) +
 * Remove, per row; Add Wedge appends a blank one. Held in an in-memory `entries` list seeded once
 * from disk -- not re-read on every rebuild -- so scrolling or changing one row's kind doesn't
 * discard another row's unsaved edits (RegionEditScreen had this bug from re-seeding on rebuild;
 * not repeating it here).
 */
public final class WheelEditScreen extends Screen {

    private static final int ROW_TOP = 40;
    private static final int ROW_H = 24;

    private final String wheelName;
    private List<ScriptWheelEntry> entries;
    private int scrollOffset;

    public WheelEditScreen(String wheelName) {
        super(Component.literal("Edit Wheel: " + wheelName));
        this.wheelName = wheelName;
    }

    @Override
    protected void init() {
        if (entries == null) entries = ScriptWheelStore.load(wheelName);
        rebuildAllWidgets();
    }

    private int visibleRows() {
        return Math.max(1, (height - ROW_TOP - 40) / ROW_H);
    }

    private void rebuildAllWidgets() {
        clearWidgets();

        addRenderableWidget(Button.builder(Component.literal("Save"), b -> onSave()).bounds(10, 10, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(88, 10, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Add Wedge"), b -> {
            entries.add(new ScriptWheelEntry("New", "script", ""));
            rebuildAllWidgets();
        }).bounds(width - 130, 10, 120, 20).build());

        int maxOffset = Math.max(0, entries.size() - visibleRows());
        scrollOffset = Math.min(scrollOffset, maxOffset);

        int y = ROW_TOP;
        for (int i = scrollOffset; i < entries.size() && i < scrollOffset + visibleRows(); i++) {
            addEntryRow(i, y);
            y += ROW_H;
        }
    }

    private void addEntryRow(int index, int y) {
        ScriptWheelEntry entry = entries.get(index);

        EditBox labelBox = new EditBox(font, 10, y, 140, 20, Component.literal("Label"));
        labelBox.setValue(entry.label == null ? "" : entry.label);
        labelBox.setResponder(v -> entry.label = v);
        addRenderableWidget(labelBox);

        addRenderableWidget(CycleButton.builder((String v) -> Component.literal(v), entry.kind == null ? "script" : entry.kind)
                .withValues(List.of("script", "macro"))
                .create(155, y, 80, 20, Component.literal("Kind"), (btn, value) -> {
                    entry.kind = value;
                    entry.name = "";
                    rebuildAllWidgets();
                }));

        List<String> names = new ArrayList<>();
        names.add("");
        names.addAll("macro".equals(entry.kind) ? MacroStore.list() : ScriptStore.list());
        String current = names.contains(entry.name) ? entry.name : "";
        addRenderableWidget(CycleButton.builder((String v) -> Component.literal(v.isEmpty() ? "(pick)" : v), current)
                .withValues(names)
                .create(240, y, 150, 20, Component.literal("Name"), (btn, value) -> entry.name = value));

        addRenderableWidget(Button.builder(Component.literal("Remove"), b -> {
            entries.remove(index);
            rebuildAllWidgets();
        }).bounds(395, y, 60, 20).build());
    }

    private void onSave() {
        ScriptWheelStore.save(wheelName, entries);
        Minecraft.getInstance().setScreen(new WheelListScreen());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxOffset = Math.max(0, entries.size() - visibleRows());
        int updated = ScrollState.scrolled(scrollOffset, maxOffset, scrollY, 1);
        if (updated != scrollOffset) {
            scrollOffset = updated;
            rebuildAllWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xC0101010);
        g.text(font, getTitle().getString(), 10, 1, 0xFFFFFFFF);
        if (entries.isEmpty()) {
            g.text(font, "No wedges yet -- Add Wedge to start.", 10, ROW_TOP, 0xFF808080);
        } else if (entries.size() > visibleRows()) {
            g.text(font, "Scroll for more.", 10, height - 14, 0xFF808080);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
