package com.ardor.client;

import com.ardor.game.BreakAreaController;
import com.ardor.game.KillAllController;
import com.ardor.planner.TaskRunner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * One row per system with an interrupted-but-resumable run (BreakAreaController/KillAllController/
 * TaskRunner -- see each one's own hasResumable/resumableSummary/resume/discardResumable). A run
 * stays listed here until it's resumed to completion, discarded, or superseded by starting a new
 * one of the same kind -- an error, a panic stop, or even a full client crash all leave it here,
 * that's the whole point.
 */
public final class ResumeWorkScreen extends Screen {

    private record Entry(String summary, Runnable resume, Runnable discard) {}

    private final Screen parent;
    private List<Entry> rowEntries = List.of();

    public ResumeWorkScreen(Screen parent) {
        super(Component.literal("Resume Interrupted Work"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 65, 10, 55, 20).build());

        rowEntries = entries();
        int y = 40;
        for (Entry e : rowEntries) {
            addRenderableWidget(Button.builder(Component.literal("Resume"), b -> {
                e.resume().run();
                onClose();
            }).bounds(width - 150, y, 70, 18).build());
            addRenderableWidget(Button.builder(Component.literal("Discard"), b -> {
                e.discard().run();
                Minecraft.getInstance().setScreen(new ResumeWorkScreen(parent));
            }).bounds(width - 75, y, 70, 18).build());
            y += 24;
        }
    }

    private static List<Entry> entries() {
        List<Entry> list = new ArrayList<>();
        if (BreakAreaController.hasResumable()) {
            list.add(new Entry(BreakAreaController.resumableSummary(), BreakAreaController::resume, BreakAreaController::discardResumable));
        }
        if (KillAllController.hasResumable()) {
            list.add(new Entry(KillAllController.resumableSummary(), KillAllController::resume, KillAllController::discardResumable));
        }
        if (TaskRunner.hasResumable()) {
            list.add(new Entry(TaskRunner.resumableSummary(), () -> TaskRunner.resume(null), TaskRunner::discardResumable));
        }
        return list;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xC0101010);
        g.text(font, getTitle().getString(), 10, 1, 0xFFFFFFFF);

        int y = 40;
        for (Entry e : rowEntries) {
            g.text(font, e.summary(), 10, y + 4, 0xFFFFFFFF);
            y += 24;
        }
        if (rowEntries.isEmpty()) {
            g.text(font, "Nothing to resume.", 10, 40, 0xFF808080);
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
