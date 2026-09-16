package com.ardor.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.Consumer;

/**
 * A script-invoked prompt (Lua UserPromptManager.multipleChoice) letting the player pick exactly
 * one option -- vanilla has no radio-button widget, so this is a column of toggle buttons with a
 * [x]/[ ] prefix, rebuilt on each pick so only one shows selected. Submit is disabled until
 * something's picked; Escape/Cancel resolves with null.
 */
public final class MultipleChoicePromptScreen extends Screen {

    private final String question;
    private final List<String> options;
    private final Consumer<String> onResult;
    private int selected = -1;
    private boolean resolved;

    public MultipleChoicePromptScreen(String question, List<String> options, Consumer<String> onResult) {
        super(Component.literal("Ardor"));
        this.question = question;
        this.options = options;
        this.onResult = onResult;
    }

    @Override
    protected void init() {
        rebuildAllWidgets();
    }

    private void rebuildAllWidgets() {
        clearWidgets();
        int startY = topRowY();
        for (int i = 0; i < options.size(); i++) {
            int idx = i;
            String label = (selected == i ? "[x] " : "[ ] ") + options.get(i);
            addRenderableWidget(Button.builder(Component.literal(label), b -> choose(idx))
                    .bounds(width / 2 - 100, startY + i * 22, 200, 20).build());
        }
        int buttonY = startY + options.size() * 22 + 10;
        Button submit = Button.builder(Component.literal("Submit"), b -> submit())
                .bounds(width / 2 - 100, buttonY, 95, 20).build();
        submit.active = selected >= 0;
        addRenderableWidget(submit);
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> cancel())
                .bounds(width / 2 + 5, buttonY, 95, 20).build());
    }

    private int topRowY() {
        return height / 2 - (options.size() * 22) / 2 - 10;
    }

    private void choose(int idx) {
        selected = idx;
        rebuildAllWidgets();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if ((event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) && selected >= 0) {
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
        if (selected < 0) return;
        resolve(options.get(selected));
    }

    private void cancel() {
        resolve(null);
    }

    private void resolve(String result) {
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
