package com.ardor.client;

import com.ardor.bridge.CompanionManagerClient;
import com.ardor.bridge.PeerClient;
import com.ardor.game.ActionDispatcher;
import com.ardor.script.ScriptStore;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The freecam orchestrator's "tell them what to do" prompt: either pick a saved, repeatable script
 * from the Task Picker list (instant, one click, no typing) or type one free-form Ardor command
 * (an ASCII verb or "script \"name\"" -- anything ActionDispatcher.execute already accepts), fanned
 * out to every target this screen was opened with. Dispatch is routed by each Target's own kind:
 * LOCAL runs in-process, PEER goes through PeerClient (works for both hand-configured and
 * auto-discovered peers -- see FreecamOrchestratorOverlay.Target's own doc), COMPANION goes through
 * CompanionManagerClient. All three are already fire-and-forget on their own executor/thread per
 * call, so fanning out to N mixed-kind targets here is already parallel, not sequential.
 */
public final class PeerCommandScreen extends Screen {

    private static final int TASK_BUTTON_H = 20;
    private static final int MAX_VISIBLE_TASKS = 6;

    private final List<FreecamOrchestratorOverlay.Target> targets;
    private List<String> scripts = List.of();
    private EditBox input;
    private boolean resolved;

    public PeerCommandScreen(List<FreecamOrchestratorOverlay.Target> targets) {
        super(Component.literal("Ardor"));
        this.targets = targets;
    }

    @Override
    protected void init() {
        clearWidgets();

        scripts = ScriptStore.list();
        int taskListH = Math.min(scripts.size(), MAX_VISIBLE_TASKS) * (TASK_BUTTON_H + 2);
        int taskListY = height / 2 - 90;
        for (int i = 0; i < Math.min(scripts.size(), MAX_VISIBLE_TASKS); i++) {
            String name = scripts.get(i);
            addRenderableWidget(Button.builder(Component.literal("▶ " + name), b -> runTask(name))
                    .bounds(width / 2 - 150, taskListY + i * (TASK_BUTTON_H + 2), 300, TASK_BUTTON_H).build());
        }
        if (scripts.isEmpty()) {
            taskListH = 0;
        } else if (scripts.size() > MAX_VISIBLE_TASKS) {
            taskListH += 12; // room for the "+N more" note drawn in extractRenderState
        }

        int boxY = taskListY + taskListH + (scripts.isEmpty() ? 0 : 10);
        input = new EditBox(font, width / 2 - 150, boxY, 300, 20, Component.literal("Command"));
        input.setHint(Component.literal("ardor command or script name"));
        addRenderableWidget(input);
        setInitialFocus(input);

        addRenderableWidget(Button.builder(Component.literal("Send"), b -> submit())
                .bounds(width / 2 - 150, boxY + 26, 145, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(width / 2 + 5, boxY + 26, 145, 20).build());
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
            submit();
            return true;
        }
        if (event.key() == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    /** Task Picker: one click on a saved script runs it on every target immediately, no typing. */
    private void runTask(String scriptName) {
        dispatch("script \"" + scriptName + "\"");
    }

    private void submit() {
        String text = input.getValue();
        if (!text.isBlank()) dispatch(text);
        else Minecraft.getInstance().gui.setScreen(null);
    }

    private void dispatch(String text) {
        if (resolved) return;
        resolved = true;
        for (FreecamOrchestratorOverlay.Target target : targets) {
            switch (target.kind()) {
                case LOCAL -> ActionDispatcher.execute(text);
                case PEER -> PeerClient.sendCommandAsync(target.host(), target.port(), text);
                case COMPANION -> CompanionManagerClient.sendCommandAsync(target.companionInstance(), text);
            }
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            String names = targets.stream().map(FreecamOrchestratorOverlay.Target::label).reduce((a, b) -> a + ", " + b).orElse("");
            mc.gui.hud.getChat().addClientSystemMessage(Component.literal(
                    "[Ardor] sent \"" + text + "\" to " + targets.size() + " target(s): " + names));
        }
        Minecraft.getInstance().gui.setScreen(null);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xC0101010);
        int qy = height / 2 - 120;
        String targetNames = targets.stream().map(FreecamOrchestratorOverlay.Target::label).reduce((a, b) -> a + ", " + b).orElse("");
        String header = "Command " + targets.size() + " target(s): " + targetNames;
        for (var line : font.split(Component.literal(header), 300)) {
            g.text(font, line, width / 2 - 150, qy, 0xFFFFFFFF);
            qy += font.lineHeight + 2;
        }
        if (!scripts.isEmpty()) {
            g.text(font, "Task Picker (saved scripts):", width / 2 - 150, height / 2 - 104, 0xFFAAAAAA);
            if (scripts.size() > MAX_VISIBLE_TASKS) {
                g.text(font, "+" + (scripts.size() - MAX_VISIBLE_TASKS) + " more (type the name below)",
                        width / 2 - 150, height / 2 - 90 + MAX_VISIBLE_TASKS * (TASK_BUTTON_H + 2), 0xFF888888);
            }
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
