package com.ardor.client;

import com.ardor.macro.MacroStore;
import com.ardor.script.ScriptStore;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Replaces ScriptKeybindScreen/MacroKeybindScreen's fixed 6-slot pools (a real vanilla KeyMapping
 * per slot, only rebindable via the Controls screen) with one unified, arbitrary-length list backed
 * by KeybindStore/DynamicKeybinds -- "dynamically create any number of keybinds," each pointing at
 * either a saved script or a saved macro. entries is the STORE's own live list (KeybindStore.load(),
 * not a copy), so an edit here is visible to DynamicKeybinds' tick-polling immediately, not just
 * after some separate reload.
 *
 * Rebind capture: clicking a row's key button starts listening for raw key events (keyPressed/
 * keyReleased below intercept everything while capturingEntry != null, so the search box and any
 * bound key elsewhere can't fire mid-capture). Single-key mode (the row's Multi checkbox off)
 * finalizes on the very first non-Escape key pressed -- snappiest for the common case. Multi-key
 * mode accumulates every key pressed into a chord and only finalizes once ALL of them are released
 * again (hold the combo, let go, done) -- waiting for release rather than press lets "Ctrl" and
 * "Ctrl+G" both exist as distinct binds without the shorter one finalizing early. Escape at any
 * point clears the row's keys instead of binding Escape itself, per its own explicit ask.
 */
public final class KeybindsScreen extends Screen {

    private static final int ROW_TOP = 40;
    private static final int ROW_H = 24;

    private List<KeybindEntry> entries;
    private String searchText = "";
    private int scrollOffset;

    private KeybindEntry capturingEntry;
    private final Set<Integer> capturedKeys = new LinkedHashSet<>(); // union of every key pressed this capture, press order
    private final Set<Integer> heldDuringCapture = new LinkedHashSet<>(); // currently-down subset, for "released everything" detection

    public KeybindsScreen() {
        super(Component.literal("Keybinds"));
    }

    @Override
    protected void init() {
        if (entries == null) entries = KeybindStore.load();
        rebuildAllWidgets();
    }

    private List<KeybindEntry> filtered() {
        if (searchText.isBlank()) return entries;
        String needle = searchText.toLowerCase();
        return entries.stream()
                .filter(e -> comboText(e).toLowerCase().contains(needle) || e.name.toLowerCase().contains(needle))
                .toList();
    }

    private int visibleRows() {
        return Math.max(1, (height - ROW_TOP - 30) / ROW_H);
    }

    private void rebuildAllWidgets() {
        clearWidgets();

        EditBox searchBox = new EditBox(font, 10, 10, 200, 20, Component.literal("Search"));
        searchBox.setHint(Component.literal("Search keys or actions"));
        searchBox.setMaxLength(200);
        searchBox.setValue(searchText);
        searchBox.setResponder(v -> { searchText = v; scrollOffset = 0; rebuildAllWidgets(); });
        addRenderableWidget(searchBox);
        setInitialFocus(searchBox);

        addRenderableWidget(Button.builder(Component.literal("Add Keybind"), b -> {
            entries.add(new KeybindEntry());
            KeybindStore.save(entries);
            searchText = "";
            scrollOffset = Integer.MAX_VALUE;
            rebuildAllWidgets();
        }).bounds(220, 10, 110, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 65, 10, 55, 20).build());

        List<KeybindEntry> shown = filtered();
        int maxOffset = Math.max(0, shown.size() - visibleRows());
        scrollOffset = Math.min(scrollOffset, maxOffset);

        int y = ROW_TOP;
        for (int i = scrollOffset; i < shown.size() && i < scrollOffset + visibleRows(); i++) {
            addEntryRow(shown.get(i), y);
            y += ROW_H;
        }
    }

    private void addEntryRow(KeybindEntry entry, int y) {
        String label = entry == capturingEntry ? "Press keys... (Esc clears)" : comboText(entry);
        addRenderableWidget(Button.builder(Component.literal(label), b -> {
            capturingEntry = entry;
            capturedKeys.clear();
            heldDuringCapture.clear();
            rebuildAllWidgets();
        }).bounds(10, y, 135, ROW_H - 2).build());

        addRenderableWidget(Checkbox.builder(Component.literal("Multi"), font)
                .pos(150, y + 2)
                .selected(entry.multiKey)
                .onValueChange((cb, v) -> { entry.multiKey = v; KeybindStore.save(entries); })
                .build());

        addRenderableWidget(CycleButton.builder((String v) -> Component.literal(v), entry.kind)
                .withValues(List.of("script", "macro"))
                .create(215, y, 70, ROW_H - 2, Component.literal("Kind"), (btn, value) -> {
                    entry.kind = value;
                    entry.name = "";
                    KeybindStore.save(entries);
                    rebuildAllWidgets();
                }));

        List<String> names = new ArrayList<>();
        names.add("");
        names.addAll("macro".equals(entry.kind) ? MacroStore.list() : ScriptStore.list());
        String currentName = names.contains(entry.name) ? entry.name : "";
        addRenderableWidget(CycleButton.builder((String v) -> Component.literal(v.isEmpty() ? "(pick)" : v), currentName)
                .withValues(names)
                .create(290, y, 160, ROW_H - 2, Component.literal("Name"), (btn, value) -> {
                    entry.name = value;
                    KeybindStore.save(entries);
                }));

        addRenderableWidget(Button.builder(Component.literal("X"), b -> {
            entries.remove(entry);
            KeybindStore.save(entries);
            rebuildAllWidgets();
        }).bounds(455, y, 20, ROW_H - 2).build());
    }

    private static String comboText(KeybindEntry entry) {
        if (entry.keys.isEmpty()) return "(unbound)";
        StringBuilder sb = new StringBuilder();
        for (int key : entry.keys) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(InputConstants.Type.KEYBOARD.getOrCreate(key).getDisplayName().getString());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ rebind capture

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (capturingEntry == null) return super.keyPressed(event);

        if (event.key() == InputConstants.KEY_ESCAPE) {
            capturingEntry.keys = new ArrayList<>();
            finishCapture();
            return true;
        }

        if (capturingEntry.multiKey) {
            capturedKeys.add(event.key());
            heldDuringCapture.add(event.key());
        } else {
            capturingEntry.keys = List.of(event.key());
            finishCapture();
        }
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (capturingEntry == null) return super.keyReleased(event);

        if (capturingEntry.multiKey) {
            heldDuringCapture.remove(event.key());
            if (heldDuringCapture.isEmpty() && !capturedKeys.isEmpty()) {
                capturingEntry.keys = new ArrayList<>(capturedKeys);
                finishCapture();
            }
        }
        return true;
    }

    private void finishCapture() {
        capturingEntry = null;
        capturedKeys.clear();
        heldDuringCapture.clear();
        KeybindStore.save(entries);
        rebuildAllWidgets();
    }

    // ------------------------------------------------------------------ render

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxOffset = Math.max(0, filtered().size() - visibleRows());
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

        List<KeybindEntry> shown = filtered();
        if (entries.isEmpty()) {
            g.text(font, "No keybinds yet -- Add Keybind to start.", 10, ROW_TOP, 0xFF808080);
        } else if (shown.isEmpty()) {
            g.text(font, "No keybinds match your search.", 10, ROW_TOP, 0xFF808080);
        } else if (shown.size() > visibleRows()) {
            g.text(font, "Scroll for more.", 10, height - 14, 0xFF808080);
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
