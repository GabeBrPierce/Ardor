package com.ardor.client;

import com.ardor.macro.MacroKeybindStore;
import com.ardor.macro.MacroStore;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One row per MacroKeybinds slot: its currently bound physical key next to a CycleButton picking which saved macro that slot plays. Mirrors ScriptKeybindScreen. */
public final class MacroKeybindScreen extends Screen {

    private static final int ROW_TOP = 40;
    private static final int ROW_H = 22;
    private static final String NONE = "(none)";

    public MacroKeybindScreen() {
        super(Component.literal("Macro Keybinds"));
    }

    @Override
    protected void init() {
        clearWidgets();

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 65, 10, 55, 20).build());

        List<String> values = new ArrayList<>();
        values.add(NONE);
        values.addAll(MacroStore.list());

        Map<Integer, String> bound = MacroKeybindStore.load();

        int y = ROW_TOP;
        for (int slot = 1; slot <= MacroKeybinds.SLOT_COUNT; slot++) {
            String current = bound.getOrDefault(slot, NONE);
            if (!values.contains(current)) current = NONE;

            int s = slot;
            addRenderableWidget(CycleButton.builder((String v) -> Component.literal(v), current)
                    .withValues(values)
                    .displayOnlyValue()
                    .create(200, y, 200, ROW_H - 2, Component.literal("Slot " + slot), (btn, value) -> {
                        Map<Integer, String> map = MacroKeybindStore.load();
                        if (value.equals(NONE)) map.remove(s); else map.put(s, value);
                        MacroKeybindStore.save(map);
                    }));

            y += ROW_H;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xC0101010);
        g.text(font, getTitle().getString(), 10, 1, 0xFFFFFFFF);

        int y = ROW_TOP;
        for (int slot = 1; slot <= MacroKeybinds.SLOT_COUNT; slot++) {
            String keyName = MacroKeybinds.key(slot).getTranslatedKeyMessage().getString();
            g.text(font, "Slot " + slot + " (" + keyName + "):", 10, y + 6, 0xFFFFFFFF);
            y += ROW_H;
        }

        g.text(font, "Rebind the physical key itself from the vanilla Controls screen, under \"Ardor\".", 10, y + 6, 0xFF808080);

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
