package com.ardor.client;

import com.ardor.region.RegionManager;
import com.ardor.region.RegionProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * "I want a GUI for creating/editing... regions" -- RegionEditScreen already
 * exists (bounds/parent text boxes) but needed a region name typed via
 * //ardor edit region <name> to get there. This is the browser: lists every
 * region in the current profile with an Edit button, plus a name box + New
 * that creates a small region centered on the player's current position
 * (a 5x5x5 cube, easy to resize afterward in RegionEditScreen) and jumps
 * straight to editing it.
 */
public final class RegionListScreen extends Screen {

    private static final int NEW_REGION_HALF_SIZE = 2; // -> a 5x5x5 default cube

    private EditBox newNameBox;
    private String statusLine = "";

    public RegionListScreen() {
        super(Component.literal("Regions"));
    }

    @Override
    protected void init() {
        clearWidgets();

        newNameBox = new EditBox(font, 10, 10, 200, 20, Component.literal("New region name"));
        newNameBox.setMaxLength(64);
        addRenderableWidget(newNameBox);
        addRenderableWidget(Button.builder(Component.literal("New (at my position)"), b -> onNew())
                .bounds(215, 10, 160, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 65, 10, 55, 20).build());

        RegionProfile profile = RegionManager.get().currentProfile();
        List<String> names = new ArrayList<>(profile.regions.keySet());
        int y = 46;
        for (String name : names) {
            String n = name;
            addRenderableWidget(Button.builder(Component.literal(n), b -> Minecraft.getInstance().setScreen(new RegionEditScreen(n)))
                    .bounds(10, y, 200, 18).build());
            y += 20;
        }
    }

    private void onNew() {
        String name = newNameBox.getValue().trim();
        if (name.isEmpty()) {
            statusLine = "Type a name first.";
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            statusLine = "No player loaded.";
            return;
        }
        BlockPos center = player.blockPosition();
        BlockPos a = center.offset(-NEW_REGION_HALF_SIZE, -NEW_REGION_HALF_SIZE, -NEW_REGION_HALF_SIZE);
        BlockPos b = center.offset(NEW_REGION_HALF_SIZE, NEW_REGION_HALF_SIZE, NEW_REGION_HALF_SIZE);
        try {
            RegionManager.get().setRegion(RegionManager.currentProfileKey(), name, a, b);
            Minecraft.getInstance().setScreen(new RegionEditScreen(name));
        } catch (RuntimeException e) {
            statusLine = e.getMessage();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xC0101010);
        g.text(font, "Profile: " + RegionManager.currentProfileKey(), 10, height - 20, 0xFFAAAAAA);
        if (!statusLine.isEmpty()) g.text(font, statusLine, 10, height - 34, 0xFFFF5555);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
