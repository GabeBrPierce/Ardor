package com.ardor.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

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
    private static final int COLUMN_GAP = 10;

    private record Row(String label, Button.OnPress onPress) {}

    private final Screen parent;

    public ArdorConfigScreen(Screen parent) {
        super(Component.literal("Ardor"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();

        List<Row> rows = List.of(
                new Row("Settings", b -> Minecraft.getInstance().setScreen(ArdorSettingsScreen.create(this))),
                new Row("Task Planner", b -> Minecraft.getInstance().setScreen(new TaskPlannerScreen())),
                new Row("Regions", b -> Minecraft.getInstance().setScreen(new RegionListScreen())),
                new Row("Events", b -> Minecraft.getInstance().setScreen(new EventConfigScreen())),
                new Row("Fetch Items", b -> Minecraft.getInstance().setScreen(new FetchItemsScreen())),
                new Row("Command Wheel", b -> Minecraft.getInstance().setScreen(new CommandWheelScreen())),
                new Row("Scripts", b -> Minecraft.getInstance().setScreen(new ScriptListScreen())),
                new Row("Script Keybinds", b -> Minecraft.getInstance().setScreen(new ScriptKeybindScreen())),
                new Row("Macros", b -> Minecraft.getInstance().setScreen(new MacroListScreen())),
                new Row("Macro Keybinds", b -> Minecraft.getInstance().setScreen(new MacroKeybindScreen())),
                new Row("Done", b -> onClose())
        );

        // Two columns instead of one long vertical stack: the row count keeps growing as features
        // are added, and a single column re-triggers the same high-GUI-Scale clipping bug fixed for
        // Settings earlier, just at the bottom edge instead of the top.
        int perColumn = (rows.size() + 1) / 2;
        int totalWidth = BUTTON_WIDTH * 2 + COLUMN_GAP;
        int startX = width / 2 - totalWidth / 2;
        int startY = Math.max(20, height / 2 - (BUTTON_HEIGHT * perColumn + ROW_GAP * (perColumn - 1)) / 2);

        for (int i = 0; i < rows.size(); i++) {
            int col = i / perColumn;
            int row = i % perColumn;
            int x = startX + col * (BUTTON_WIDTH + COLUMN_GAP);
            int y = startY + row * ROW_GAP;
            Row r = rows.get(i);
            addRenderableWidget(Button.builder(Component.literal(r.label()), r.onPress())
                    .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xC0101010);
        g.text(font, getTitle().getString(), 10, 1, 0xFFFFFFFF);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
