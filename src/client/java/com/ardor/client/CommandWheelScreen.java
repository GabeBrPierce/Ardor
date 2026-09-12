package com.ardor.client;

import com.ardor.game.GrindModeController;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * "Add a command wheel for things like 'Grind Mobs'." A first, plain-list cut at this -- not a
 * literal radial pie menu yet, just a quick-select screen of standing behavior modes, opened and
 * closed with one key the same way every other screen here works. Grind Mobs is the first entry
 * (see GrindModeController); more modes get their own button here as they're built, same pattern.
 */
public final class CommandWheelScreen extends Screen {

    private Button grindMobsButton;

    public CommandWheelScreen() {
        super(Component.literal("Command Wheel"));
    }

    @Override
    protected void init() {
        clearWidgets();
        grindMobsButton = Button.builder(grindMobsLabel(), b -> toggleGrindMobs())
                .bounds(10, 10, 200, 20).build();
        addRenderableWidget(grindMobsButton);
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(10, 36, 200, 20).build());
    }

    private void toggleGrindMobs() {
        if (GrindModeController.isActive()) {
            GrindModeController.stop();
        } else {
            GrindModeController.start();
        }
        grindMobsButton.setMessage(grindMobsLabel());
    }

    private static Component grindMobsLabel() {
        return Component.literal("Grind Mobs: " + (GrindModeController.isActive() ? "ON" : "OFF"));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xC0101010);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
