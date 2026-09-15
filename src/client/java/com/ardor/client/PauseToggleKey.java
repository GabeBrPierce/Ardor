package com.ardor.client;

import com.ardor.planner.TaskOrchestrator;
import com.ardor.planner.TaskRunner;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

/**
 * "I want to be able to pause execution and resume." Already existed as a button inside
 * TaskPlannerScreen (RUNNER.pause()/resume() + TaskOrchestrator.pause()/resume() so its own status
 * text reflects it too) -- this is the same toggle, reachable without opening that screen, for the
 * quest-tracker HUD this pairs with. Bound to P by default. Toggles the shared TaskRunner
 * regardless of whether TaskOrchestrator is driving it (an event-triggered interrupt can also be
 * driving TaskRunner.shared() outside of an orchestration round), same reasoning
 * TaskPlannerScreen's own onPauseToggle already documents.
 */
public final class PauseToggleKey {

    private static final KeyMapping KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.ardor.pausetoggle", InputConstants.KEY_P, ArdorKeyCategory.ARDOR));

    private PauseToggleKey() {}

    public static void register() {
        KeybindTicker.add(KEY, PauseToggleKey::toggle);
    }

    private static void toggle() {
        TaskRunner runner = TaskRunner.shared();
        if (!runner.isActive()) {
            StatusIndicator.show("Nothing running to pause.");
            return;
        }
        if (runner.isPaused()) {
            runner.resume();
            if (TaskOrchestrator.isActive()) TaskOrchestrator.resume();
            else StatusIndicator.show("Resumed.");
        } else {
            runner.pause();
            if (TaskOrchestrator.isActive()) TaskOrchestrator.pause();
            else StatusIndicator.show("Paused.");
        }
    }
}
