package com.ardor.client;

import com.ardor.script.ScriptStore;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Plain-text Lua source editor for one script, opened from ScriptListScreen. Wraps vanilla
 * MultiLineEditBox (MC 26.1.2 ships a real multi-line editable/scrollable text area -- wrapping,
 * selection, click/drag, word navigation and clipboard all come free) rather than hand-rolling one.
 * No syntax highlighting or autocomplete: deliberate first pass, see TODO.md.
 */
public final class ScriptEditScreen extends Screen {

    private static final int EDITOR_TOP = 34;
    private static final int FOOTER_H = 26;
    private static final int INDENT = 4;

    private final String scriptName;
    private String source;
    private String statusLine = "";
    private MultiLineEditBox editor;

    public ScriptEditScreen(String scriptName) {
        super(Component.literal("Edit Script: " + scriptName));
        this.scriptName = scriptName;
        this.source = ScriptStore.exists(scriptName) ? ScriptStore.load(scriptName) : "";
    }

    @Override
    protected void init() {
        // init() also runs on window resize, so the editor is seeded from the held `source` field
        // (kept current by the value listener), never re-read from disk.
        editor = MultiLineEditBox.builder()
                .setX(10)
                .setY(EDITOR_TOP)
                .setPlaceholder(Component.literal("-- Lua script source"))
                .build(font, width - 20, height - EDITOR_TOP - FOOTER_H, Component.literal("Script source"));
        editor.setValue(source);
        editor.setValueListener(v -> source = v);
        addRenderableWidget(editor);
        setInitialFocus(editor);

        addRenderableWidget(Button.builder(Component.literal("Save"), b -> onSave())
                .bounds(width - 125, 10, 55, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 65, 10, 55, 20).build());
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // MultilineTextField doesn't handle Tab at all, so without this it falls through to Screen's
        // focus cycling. Four space charTyped calls rather than a literal tab: consistent render width.
        if (event.key() == GLFW.GLFW_KEY_TAB && editor.isFocused()) {
            for (int i = 0; i < INDENT; i++) {
                editor.charTyped(new CharacterEvent(' '));
            }
            return true;
        }
        return super.keyPressed(event);
    }

    private void onSave() {
        ScriptStore.save(scriptName, source);
        statusLine = "Saved " + scriptName + ".lua";
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xC0101010);
        g.text(font, getTitle().getString(), 10, 1, 0xFFFFFFFF);
        g.text(font, statusLine, 10, height - 18, 0xFFAAAAAA);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
