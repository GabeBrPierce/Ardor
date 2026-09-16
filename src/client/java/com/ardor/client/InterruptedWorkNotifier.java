package com.ardor.client;

import com.ardor.game.BreakAreaController;
import com.ardor.game.KillAllController;
import com.ardor.planner.TaskRunner;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import java.util.ArrayList;
import java.util.List;

/**
 * "If we get interrupted while doing something... I would like a way to resume from where we left
 * off." On every world join, checks whether BreakAreaController/KillAllController/TaskRunner left a
 * resumable run behind (an error, a panic stop, or the client closing/crashing mid-run -- see each
 * one's own hasResumable() doc) for THIS world/server, and surfaces it in chat with a pointer to
 * ResumeWorkScreen rather than leaving it silently sitting on disk until someone happens to open the
 * Ardor menu.
 */
public final class InterruptedWorkNotifier {

    private InterruptedWorkNotifier() {}

    public static void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            List<String> summaries = new ArrayList<>();
            if (BreakAreaController.hasResumable()) summaries.add(BreakAreaController.resumableSummary());
            if (KillAllController.hasResumable()) summaries.add(KillAllController.resumableSummary());
            if (TaskRunner.hasResumable()) summaries.add(TaskRunner.resumableSummary());
            if (summaries.isEmpty()) return;

            StatusIndicator.show("Found interrupted work from last session: " + String.join("; ", summaries)
                    + ". Open the Ardor menu -> Resume Interrupted Work to continue or discard.");
        });
    }
}
