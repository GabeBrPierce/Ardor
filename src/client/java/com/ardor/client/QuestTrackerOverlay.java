package com.ardor.client;

import com.ardor.game.BreakAreaController;
import com.ardor.game.KillAllController;
import com.ardor.game.PathfindingController;
import com.ardor.planner.TaskOrchestrator;
import com.ardor.planner.TaskRunner;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * "Where is the UI for the quests? It should ALWAYS display what is currently happening." Real
 * gap, found by reading the code: this originally only ever read TaskOrchestrator's state, but a
 * LOT of autonomous activity in this mod runs entirely independent of TaskOrchestrator (and even
 * of TaskRunner) -- Break Blocks Within (BreakAreaController) and Kill All/Kill Hostile Mobs
 * (KillAllController) are both driven directly by AreaSelectionMode/SingleSelectionMode's own tick
 * loops, never touching the orchestrator at all. So the overlay went dark during exactly the kind
 * of unattended, autonomous action the user was watching for status on.
 *
 * Now checks, in priority order, whichever of these is ACTUALLY driving the bot right now:
 *   1. TaskOrchestrator (a goal-driven Run/Auto-Run) -- Main Quest + current step + step progress.
 *   2. BreakAreaController (Break Blocks Within) -- real brokenCount/totalCount progress.
 *   3. KillAllController (Kill All / Kill Hostile Mobs) -- no countable total (open-ended re-scan
 *      loop), just an activity line.
 *   4. Plain TaskRunner activity (a direct SAY:/DO: voice command, or an event-triggered urgent
 *      task -- both bypass TaskOrchestrator but still go through TaskRunner) -- its own
 *      taskDescription as a generic activity line.
 * Renders nothing only when NONE of the above is active -- genuinely idle, nothing happening.
 */
public final class QuestTrackerOverlay {

    private static final int LEFT_MARGIN = 4;
    private static final int TOP_MARGIN = 4;
    private static final int LINE_H = 12;
    private static final int BAR_W = 160;
    private static final int BAR_H = 6;
    private static final int PADDING = 4;

    private QuestTrackerOverlay() {}

    public static void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("ardor", "quest_tracker_overlay"),
                QuestTrackerOverlay::render);
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker tracker) {
        String mainLine;
        String subLine = null;
        double barProgress = -1; // -1 = no bar for this line
        int barColor = 0xFF55CC55;

        String goal = TaskOrchestrator.goal();
        if (!goal.isEmpty()) {
            mainLine = "Main Quest: " + goal;
            String step = TaskOrchestrator.currentStepText();
            if (!step.isEmpty()) subLine = "- " + step;
            barProgress = taskRunnerStepProgress();
        } else if (BreakAreaController.isActive()) {
            mainLine = "Breaking blocks within area";
            int total = BreakAreaController.totalCount();
            int broken = BreakAreaController.brokenCount();
            subLine = broken + " / " + total + " broken";
            if (total > 0) barProgress = (double) broken / total;
        } else if (KillAllController.isActive()) {
            mainLine = "Clearing hostile mobs";
        } else if (TaskRunner.shared().isActive()) {
            JsonObject status = TaskRunner.shared().status();
            mainLine = status.has("taskDescription") ? status.get("taskDescription").getAsString() : "Working...";
            barProgress = taskRunnerStepProgress();
        } else {
            return; // genuinely idle -- nothing happening right now
        }

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        double navProgress = PathfindingController.baritoneNavProgress();
        boolean showNavBar = navProgress >= 0;

        int lines = 1 + (subLine != null ? 1 : 0);
        int boxW = Math.max(Math.max(font.width(mainLine), subLine != null ? font.width(subLine) : 0), BAR_W) + PADDING * 2;
        int boxH = lines * LINE_H + PADDING * 2
                + (barProgress >= 0 ? BAR_H + 4 : 0)
                + (showNavBar ? BAR_H + 4 : 0);

        int x = LEFT_MARGIN;
        int y = TOP_MARGIN;
        g.fill(x, y, x + boxW, y + boxH, 0xC0101010);

        int textX = x + PADDING;
        int textY = y + PADDING;
        g.text(font, mainLine, textX, textY, 0xFFFFD700);
        textY += LINE_H;
        if (subLine != null) {
            g.text(font, subLine, textX, textY, 0xFFFFFFFF);
            textY += LINE_H;
        }
        if (barProgress >= 0) {
            drawBar(g, textX, textY, barProgress, barColor);
            textY += BAR_H + 4;
        }
        if (showNavBar) {
            drawBar(g, textX, textY, navProgress, 0xFF5599FF);
        }
    }

    private static double taskRunnerStepProgress() {
        JsonObject status = TaskRunner.shared().status();
        if (status.has("totalTasks") && status.get("totalTasks").getAsInt() > 0) {
            return status.get("taskIndex").getAsDouble() / status.get("totalTasks").getAsDouble();
        }
        return -1;
    }

    private static void drawBar(GuiGraphicsExtractor g, int x, int y, double fraction, int fillColor) {
        g.fill(x, y, x + BAR_W, y + BAR_H, 0xFF303030);
        int filledW = (int) Math.round(BAR_W * Math.max(0.0, Math.min(1.0, fraction)));
        if (filledW > 0) g.fill(x, y, x + filledW, y + BAR_H, fillColor);
    }
}
