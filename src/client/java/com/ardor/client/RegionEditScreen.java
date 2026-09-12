package com.ardor.client;

import com.ardor.region.Region;
import com.ardor.region.RegionManager;
import com.ardor.region.RegionProfile;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * "//ardor edit region <name>": text boxes for a region's parent and bounds,
 * in the current profile (RegionManager.currentProfileKey()). Event-task
 * bindings are edited on EventConfigScreen instead, not here, matching the
 * request's own split between "edit region" (bounds/parent) and "configure
 * events" (region+event dropdown -> task text) screens.
 */
public final class RegionEditScreen extends Screen {

    private static final int FIELD_W = 100;
    private static final int FIELD_H = 18;

    private final String regionName;
    private final String profileKey;
    private EditBox parentBox, minXBox, minYBox, minZBox, maxXBox, maxYBox, maxZBox;
    private Checkbox excludeItemSourcesBox, excludeImplicitRetrievalBox, excludeImplicitManufacturingBox;
    private String statusLine = "";

    public RegionEditScreen(String regionName) {
        super(Component.literal("Edit Region: " + regionName));
        this.regionName = regionName;
        this.profileKey = RegionManager.currentProfileKey();
    }

    @Override
    protected void init() {
        clearWidgets();
        RegionProfile profile = RegionManager.get().currentProfile();
        Region region = profile.regions.get(regionName);
        boolean isNew = region == null;
        if (isNew) {
            region = new Region(regionName, null, BlockPos.ZERO, BlockPos.ZERO);
        }

        int y = 30;
        parentBox = labeledBox(y, "Parent (blank = global):", region.parent == null ? "" : region.parent);
        y += 40;

        boolean global = region.isGlobal();
        minXBox = labeledBox(y, "Min X / Y / Z:", global ? "" : String.valueOf(region.minX), 10);
        minYBox = smallBox(y, 120, global ? "" : String.valueOf(region.minY));
        minZBox = smallBox(y, 230, global ? "" : String.valueOf(region.minZ));
        y += 30;
        maxXBox = labeledBox(y, "Max X / Y / Z:", global ? "" : String.valueOf(region.maxX), 10);
        maxYBox = smallBox(y, 120, global ? "" : String.valueOf(region.maxY));
        maxZBox = smallBox(y, 230, global ? "" : String.valueOf(region.maxZ));
        y += 40;

        if (region.isGlobal()) {
            statusLine = "'global' is the implicit unbounded root region -- bounds are ignored for it.";
        }

        excludeItemSourcesBox = addRenderableWidget(Checkbox.builder(Component.literal("Exclude Item Sources (don't auto-index containers/cauldrons here)"), font)
                .pos(10, y).selected(region.excludeItemSources).build());
        y += 22;
        excludeImplicitRetrievalBox = addRenderableWidget(Checkbox.builder(Component.literal("Exclude Implicit Item Retrieval (don't auto-grab tools/food from containers here)"), font)
                .pos(10, y).selected(region.excludeImplicitItemRetrieval).build());
        y += 22;
        excludeImplicitManufacturingBox = addRenderableWidget(Checkbox.builder(Component.literal("Exclude Implicit Item Manufacturing (don't auto-craft/smelt here)"), font)
                .pos(10, y).selected(region.excludeImplicitItemManufacturing).build());
        y += 30;

        addRenderableWidget(Button.builder(Component.literal("Save"), b -> onSave())
                .bounds(10, y, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(88, y, 70, 20).build());
    }

    private EditBox labeledBox(int y, String label, String value) {
        return labeledBox(y, label, value, 150);
    }

    private EditBox labeledBox(int y, String label, String value, int x) {
        EditBox box = new EditBox(font, x, y, FIELD_W, FIELD_H, Component.literal(label));
        box.setValue(value);
        box.setMaxLength(64);
        return addRenderableWidget(box);
    }

    private EditBox smallBox(int y, int x, String value) {
        EditBox box = new EditBox(font, x, y, 90, FIELD_H, Component.literal(""));
        box.setValue(value);
        box.setMaxLength(16);
        return addRenderableWidget(box);
    }

    private void onSave() {
        try {
            String parent = parentBox.getValue().trim();
            BlockPos a = new BlockPos(parseInt(minXBox), parseInt(minYBox), parseInt(minZBox));
            BlockPos b = new BlockPos(parseInt(maxXBox), parseInt(maxYBox), parseInt(maxZBox));

            RegionProfile profile = RegionManager.get().currentProfile();
            Region region = profile.regions.get(regionName);
            if (region == null || region.isGlobal() && !regionName.equals("global")) {
                region = new Region();
                region.name = regionName;
                region.eventTasks = new java.util.LinkedHashMap<>();
                profile.regions.put(regionName, region);
            }
            region.parent = parent.isEmpty() ? null : parent;
            if (!regionName.equals("global")) {
                region.setBounds(a, b);
            }
            region.excludeItemSources = excludeItemSourcesBox.selected();
            region.excludeImplicitItemRetrieval = excludeImplicitRetrievalBox.selected();
            region.excludeImplicitItemManufacturing = excludeImplicitManufacturingBox.selected();
            RegionManager.get().save();
            statusLine = "Saved.";
        } catch (NumberFormatException e) {
            statusLine = "Bounds must be whole numbers.";
        }
    }

    private static int parseInt(EditBox box) {
        String v = box.getValue().trim();
        return v.isEmpty() ? 0 : Integer.parseInt(v);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xC0101010);
        g.text(font, "Region: " + regionName + "  (profile: " + profileKey + ")", 10, 10, 0xFFFFFFFF);
        g.text(font, statusLine, 10, height - 20, 0xFFAAAAAA);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
