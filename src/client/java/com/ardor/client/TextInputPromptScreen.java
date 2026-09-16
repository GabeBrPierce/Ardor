package com.ardor.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/**
 * A script-invoked prompt (Lua UserPromptManager.textInput) asking the player a free-text
 * question. Enter or Submit resolves with the typed text; Escape or Cancel resolves with null,
 * which ScriptEngine turns into a Lua nil the same way a timed-out ArdorUsers field read does.
 */
public final class TextInputPromptScreen extends Screen {

    private final String question;
    private final Consumer<String> onResult;
    private boolean resolved;
    private EditBox input;

    public TextInputPromptScreen(String question, Consumer<String> onResult) {
        super(Component.literal("Ardor"));
        this.question = question;
        this.onResult = onResult;
    }

    @Override
    protected void init() {
        clearWidgets();
        int boxY = height / 2 - 10;
        input = new EditBox(font, width / 2 - 150, boxY, 300, 20, Component.literal("Answer"));
        input.setHint(Component.literal("Type your answer"));
        addRenderableWidget(input);
        setInitialFocus(input);

        addRenderableWidget(Button.builder(Component.literal("Submit"), b -> submit())
                .bounds(width / 2 - 150, boxY + 26, 145, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> cancel())
                .bounds(width / 2 + 5, boxY + 26, 145, 20).build());
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
        resolve(input.getValue());
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
        int qy = height / 2 - 40;
        for (var line : font.split(Component.literal(question), 300)) {
            g.text(font, line, width / 2 - 150, qy, 0xFFFFFFFF);
            qy += font.lineHeight + 2;
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
