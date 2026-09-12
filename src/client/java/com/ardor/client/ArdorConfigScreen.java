package com.ardor.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The mod's single Mod Menu entry point (see ArdorModMenuIntegration) -- replaces the five
 * separate "press a key to open a screen" keybinds that used to exist for Task Planner/Region
 * List/Event Config/Fetch Items/Command Wheel. Settings themselves are a real Cloth Config screen
 * (ArdorSettingsScreen), reached via the button below; the tool screens aren't "configuration" in
 * that sense (live task running, an item grid, 3D region editing, async status polling) -- Cloth
 * Config has no equivalent for that, so they stay their own Screen classes, just reached from here
 * instead of a dedicated key. PushToTalk is unaffected: it's a hold-to-act gameplay key, not a
 * screen-opener, so nothing about this migration touches it. (PingKey itself was later replaced
 * by PickWheelKey/ArdorWheelScreen -- see their class docs.)
 */
public final class ArdorConfigScreen extends Screen {

    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 20;
    private static final int ROW_GAP = 24;

    private final Screen parent;

    public ArdorConfigScreen(Screen parent) {
        super(Component.literal("Ardor"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();
        int x = width / 2 - BUTTON_WIDTH / 2;
        int y = height / 2 - (BUTTON_HEIGHT * 7 + ROW_GAP * 6) / 2;

        y = addRow(x, y, "Settings", b -> Minecraft.getInstance().setScreen(ArdorSettingsScreen.create(this)));
        y = addRow(x, y, "Task Planner", b -> Minecraft.getInstance().setScreen(new TaskPlannerScreen()));
        y = addRow(x, y, "Regions", b -> Minecraft.getInstance().setScreen(new RegionListScreen()));
        y = addRow(x, y, "Events", b -> Minecraft.getInstance().setScreen(new EventConfigScreen()));
        y = addRow(x, y, "Fetch Items", b -> Minecraft.getInstance().setScreen(new FetchItemsScreen()));
        y = addRow(x, y, "Command Wheel", b -> Minecraft.getInstance().setScreen(new CommandWheelScreen()));
        addRow(x, y, "Done", b -> onClose());
    }

    private int addRow(int x, int y, String label, Button.OnPress onPress) {
        addRenderableWidget(Button.builder(Component.literal(label), onPress)
                .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        return y + ROW_GAP;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
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
