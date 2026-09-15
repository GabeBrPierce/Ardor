package com.ardor.client;

import com.ardor.event.ScriptEventBindings;
import com.ardor.event.ScriptEventDef;
import com.ardor.event.ScriptEventStore;
import com.ardor.macro.MacroStore;
import com.ardor.script.ScriptStore;
import com.ardor.script.ScriptWheelEntry;
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
 * Interval (ticks) + which saved script serves as the predicate, then a subscriber wedge list
 * (script/macro + target, same shape WheelEditScreen already uses -- reuses ScriptWheelEntry
 * directly). Save persists via ScriptEventStore AND calls ScriptEventBindings.reregister so the
 * change is live immediately, not just after a restart. Held in-memory state, same "don't re-seed
 * from disk on rebuild" fix RegionEditScreen/WheelEditScreen already apply.
 */
public final class ScriptEventEditScreen extends Screen {

    private static final int ROW_TOP = 100;
    private static final int ROW_H = 24;

    private final String eventName;
    private ScriptEventDef def;
    private int scrollOffset;

    public ScriptEventEditScreen(String eventName) {
        super(Component.literal("Edit Script Event: " + eventName));
        this.eventName = eventName;
    }

    @Override
    protected void init() {
        if (def == null) def = ScriptEventStore.load(eventName);
        rebuildAllWidgets();
    }

    private int visibleRows() {
        return Math.max(1, (height - ROW_TOP - 30) / ROW_H);
    }

    private void rebuildAllWidgets() {
        clearWidgets();

        addRenderableWidget(Button.builder(Component.literal("Save"), b -> onSave()).bounds(10, 10, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(88, 10, 70, 20).build());

        EditBox intervalBox = new EditBox(font, 10, 40, 100, 20, Component.literal("Interval ticks"));
        intervalBox.setValue(Integer.toString(def.intervalTicks));
        intervalBox.setResponder(v -> {
            try {
                def.intervalTicks = v.isEmpty() ? 1 : Integer.parseInt(v);
            } catch (NumberFormatException ignored) {
                // leave def.intervalTicks at whatever it last validly was
            }
        });
        addRenderableWidget(intervalBox);

        List<String> scripts = new ArrayList<>();
        scripts.add("");
        scripts.addAll(ScriptStore.list());
        String currentPredicate = scripts.contains(def.predicateScript) ? def.predicateScript : "";
        addRenderableWidget(CycleButton.builder((String v) -> Component.literal(v.isEmpty() ? "(pick predicate script)" : v), currentPredicate)
                .withValues(scripts)
                .create(120, 40, 220, 20, Component.literal("Predicate"), (btn, value) -> def.predicateScript = value));

        addRenderableWidget(Button.builder(Component.literal("Add Subscriber"), b -> {
            def.subscribers.add(new ScriptWheelEntry("Subscriber", "script", ""));
            rebuildAllWidgets();
        }).bounds(width - 140, 40, 130, 20).build());

        int maxOffset = Math.max(0, def.subscribers.size() - visibleRows());
        scrollOffset = Math.min(scrollOffset, maxOffset);

        int y = ROW_TOP;
        for (int i = scrollOffset; i < def.subscribers.size() && i < scrollOffset + visibleRows(); i++) {
            addSubscriberRow(i, y);
            y += ROW_H;
        }
    }

    private void addSubscriberRow(int index, int y) {
        ScriptWheelEntry entry = def.subscribers.get(index);

        addRenderableWidget(CycleButton.builder((String v) -> Component.literal(v), entry.kind == null ? "script" : entry.kind)
                .withValues(List.of("script", "macro"))
                .create(10, y, 80, 20, Component.literal("Kind"), (btn, value) -> {
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
                .create(95, y, 200, 20, Component.literal("Target"), (btn, value) -> entry.name = value));

        addRenderableWidget(Button.builder(Component.literal("Remove"), b -> {
            def.subscribers.remove(index);
            rebuildAllWidgets();
        }).bounds(300, y, 60, 20).build());
    }

    private void onSave() {
        def.name = eventName;
        ScriptEventStore.save(def);
        ScriptEventBindings.reregister(eventName);
        Minecraft.getInstance().setScreen(new ScriptEventListScreen());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxOffset = Math.max(0, def.subscribers.size() - visibleRows());
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
        g.text(font, "Interval (ticks):", 10, 30, 0xFFAAAAAA);
        g.text(font, "Predicate script (must return true/false, no blocking calls):", 120, 30, 0xFFAAAAAA);
        if (def.subscribers.isEmpty()) {
            g.text(font, "No subscribers yet -- Add Subscriber to run something when this event fires.", 10, ROW_TOP, 0xFF808080);
        } else if (def.subscribers.size() > visibleRows()) {
            g.text(font, "Scroll for more.", 10, height - 14, 0xFF808080);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
