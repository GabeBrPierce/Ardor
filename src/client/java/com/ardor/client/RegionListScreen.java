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
        newNameBox.setHint(Component.literal("New region name"));
        newNameBox.setMaxLength(64);
        addRenderableWidget(newNameBox);

        FlowLayout flow = new FlowLayout(215, 10, width - 75, 20, 4, 4);
        int[] pos = flow.next(160);
        addRenderableWidget(Button.builder(Component.literal("New (at my position)"), b -> onNew())
                .bounds(pos[0], pos[1], 160, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 65, 10, 55, 20).build());

        RegionProfile profile = RegionManager.get().currentProfile();

        // Combat aggressiveness (Passive Mobs/Hostile Mobs/Players) is a per-region Region
        // Behavior Setting now, not a separate profile-wide toggle -- edit the "global" row below
        // (always present, see RegionManager.ensureGlobalRegion) to set this profile's own default.
        List<String> names = new ArrayList<>(profile.regions.keySet());
        int y = Math.max(40, flow.bottom() + 4);
        for (String name : names) {
            String n = name;
            boolean isGlobal = "global".equals(n);
            addRenderableWidget(Button.builder(Component.literal(n), b -> Minecraft.getInstance().setScreen(new RegionEditScreen(n)))
                    .bounds(10, y, isGlobal ? 200 : 160, 18).build());
            if (!isGlobal) {
                addRenderableWidget(Button.builder(Component.literal("Delete"), b -> onDelete(n))
                        .bounds(174, y, 46, 18).build());
            }
            y += 20;
        }
    }

    private void onDelete(String name) {
        try {
            RegionManager.get().deleteRegion(RegionManager.currentProfileKey(), name);
            init();
        } catch (RuntimeException e) {
            statusLine = e.getMessage();
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
        g.text(font, getTitle().getString(), 10, 1, 0xFFFFFFFF);
        g.text(font, "Profile: " + RegionManager.currentProfileKey(), 10, height - 20, 0xFFAAAAAA);
        if (!statusLine.isEmpty()) g.text(font, statusLine, 10, height - 34, 0xFFFF5555);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
