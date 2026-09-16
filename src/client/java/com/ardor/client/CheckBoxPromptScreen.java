package com.ardor.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A script-invoked prompt (Lua UserPromptManager.checkbox) letting the player pick any number of
 * options (including none). Submit resolves with the list of checked labels (possibly empty);
 * Escape/Cancel resolves with null -- distinct from "submitted with nothing checked."
 */
public final class CheckBoxPromptScreen extends Screen {

    private final String question;
    private final List<String> options;
    private final Consumer<List<String>> onResult;
    private final boolean[] checked;
    private boolean resolved;

    public CheckBoxPromptScreen(String question, List<String> options, Consumer<List<String>> onResult) {
        super(Component.literal("Ardor"));
        this.question = question;
        this.options = options;
        this.onResult = onResult;
        this.checked = new boolean[options.size()];
    }

    @Override
    protected void init() {
        clearWidgets();
        int startY = topRowY();
        for (int i = 0; i < options.size(); i++) {
            int idx = i;
            addRenderableWidget(Checkbox.builder(Component.literal(options.get(i)), font)
                    .pos(width / 2 - 100, startY + i * 22)
                    .selected(checked[i])
                    .onValueChange((cb, value) -> checked[idx] = value)
                    .build());
        }
        int buttonY = startY + options.size() * 22 + 10;
        addRenderableWidget(Button.builder(Component.literal("Submit"), b -> submit())
                .bounds(width / 2 - 100, buttonY, 95, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> cancel())
                .bounds(width / 2 + 5, buttonY, 95, 20).build());
    }

    private int topRowY() {
        return height / 2 - (options.size() * 22) / 2 - 10;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            submit();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            cancel();
            return true;
        }
        return super.keyPressed(event);
    }

    private void submit() {
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) if (checked[i]) selected.add(options.get(i));
        resolve(selected);
    }

    private void cancel() {
        resolve(null);
    }

    private void resolve(List<String> result) {
        if (resolved) return;
        resolved = true;
        onResult.accept(result);
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public void onClose() {
        cancel();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xC0101010);
        int qy = topRowY() - 30;
        for (var line : font.split(Component.literal(question), 260)) {
            g.text(font, line, width / 2 - 100, qy, 0xFFFFFFFF);
            qy += font.lineHeight + 2;
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
