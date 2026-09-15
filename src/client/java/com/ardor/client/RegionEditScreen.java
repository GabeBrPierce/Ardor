package com.ardor.client;

import com.ardor.region.Aggressiveness;
import com.ardor.region.Region;
import com.ardor.region.RegionManager;
import com.ardor.region.RegionProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;

/**
 * "//ardor edit region <name>": name/parent/bounds editor for a region in the current profile
 * (RegionManager.currentProfileKey()). Content can exceed the window height, so this screen
 * scrolls (mouse wheel); rows fully outside the viewport are simply left out of that rebuild
 * rather than clipped.
 */
public final class RegionEditScreen extends Screen {

    private static final int FIELD_W = 100;
    private static final int FIELD_H = 18;
    private static final int VIEWPORT_TOP = 30;
    private static final int VIEWPORT_BOTTOM_MARGIN = 30; // leaves room for the status line below the scrollable area

    private final String regionName;
    private final String profileKey;
    private EditBox nameBox;
    private String statusLine = "";
    private int scrollOffset = 0;
    private int maxScroll = 0;

    // Held field state for every editable value, seeded once from the saved Region (see
    // fieldsInitialized below) and updated live by each widget's responder/listener. rebuildAllWidgets
    // re-seeds new widgets from these on every scroll, never from the Region directly, so scrolling
    // can't discard an in-progress edit.
    private boolean fieldsInitialized = false;
    private String nameText, parentText, minXText, minYText, minZText, maxXText, maxYText, maxZText;
    private boolean excludeItemSourcesVal, excludeImplicitRetrievalVal, excludeImplicitManufacturingVal, disableImplicitDestructionVal;
    private Aggressiveness passiveMobsValue, hostileMobsValue, playersValue;
    private static final Aggressiveness[] CYCLE = {null, Aggressiveness.OFF, Aggressiveness.REACTIVE, Aggressiveness.PROACTIVE};

    public RegionEditScreen(String regionName) {
        super(Component.literal("Edit Region: " + regionName));
        this.regionName = regionName;
        this.profileKey = RegionManager.currentProfileKey();
    }

    @Override
    protected void init() {
        rebuildAllWidgets();
    }

    private void rebuildAllWidgets() {
        clearWidgets();
        RegionProfile profile = RegionManager.get().currentProfile();
        Region region = profile.regions.get(regionName);
        boolean isNew = region == null;
        if (isNew) {
            region = new Region(regionName, null, BlockPos.ZERO, BlockPos.ZERO);
        }
        Region finalRegion = region;
        boolean global = region.isGlobal();

        if (!fieldsInitialized) {
            nameText = regionName;
            parentText = region.parent == null ? "" : region.parent;
            minXText = global ? "" : String.valueOf(region.minX);
            minYText = global ? "" : String.valueOf(region.minY);
            minZText = global ? "" : String.valueOf(region.minZ);
            maxXText = global ? "" : String.valueOf(region.maxX);
            maxYText = global ? "" : String.valueOf(region.maxY);
            maxZText = global ? "" : String.valueOf(region.maxZ);
            excludeItemSourcesVal = region.excludeItemSources;
            excludeImplicitRetrievalVal = region.excludeImplicitItemRetrieval;
            excludeImplicitManufacturingVal = region.excludeImplicitItemManufacturing;
            disableImplicitDestructionVal = region.disableImplicitDestruction;
            passiveMobsValue = region.passiveMobs;
            hostileMobsValue = region.hostileMobs;
            playersValue = region.players;
            fieldsInitialized = true;
        }

        int viewportBottom = height - VIEWPORT_BOTTOM_MARGIN;
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        int y = VIEWPORT_TOP - scrollOffset;
        boolean isGlobalRegion = regionName.equals("global");
        nameBox = labeledBox(y, viewportBottom, "Name:", nameText, v -> nameText = v);
        nameBox.active = !isGlobalRegion; // "global" is the implicit root every profile/RegionManager code hardcodes by that exact name -- can't be renamed
        y += 40;

        labeledBox(y, viewportBottom, "Parent (blank = global):", parentText, v -> parentText = v);
        y += 40;

        labeledBox(y, viewportBottom, "Min X / Y / Z:", minXText, 10, v -> minXText = v);
        smallBox(y, viewportBottom, 120, minYText, "Y", v -> minYText = v);
        smallBox(y, viewportBottom, 230, minZText, "Z", v -> minZText = v);
        y += 30;
        labeledBox(y, viewportBottom, "Max X / Y / Z:", maxXText, 10, v -> maxXText = v);
        smallBox(y, viewportBottom, 120, maxYText, "Y", v -> maxYText = v);
        smallBox(y, viewportBottom, 230, maxZText, "Z", v -> maxZText = v);
        y += 40;

        if (global) {
            statusLine = "'global' is the implicit unbounded root region -- bounds are ignored for it.";
        }

        addCheckbox(y, viewportBottom, "Exclude Item Sources (don't auto-index containers/cauldrons here)", excludeItemSourcesVal, v -> excludeItemSourcesVal = v);
        y += 22;
        addCheckbox(y, viewportBottom, "Exclude Implicit Item Retrieval (don't auto-grab tools/food from containers here)", excludeImplicitRetrievalVal, v -> excludeImplicitRetrievalVal = v);
        y += 22;
        addCheckbox(y, viewportBottom, "Exclude Implicit Item Manufacturing (don't auto-craft/smelt here)", excludeImplicitManufacturingVal, v -> excludeImplicitManufacturingVal = v);
        y += 22;
        addCheckbox(y, viewportBottom, "Disable Implicit Destruction (don't decide to break blocks to reach a target here)", disableImplicitDestructionVal, v -> disableImplicitDestructionVal = v);
        y += 30;

        addAggressivenessRow(y, viewportBottom, "Passive Mobs", passiveMobsValue, v -> passiveMobsValue = v,
                RegionManager.get().effectiveAggressiveness(profileKey, finalRegion, r -> r.passiveMobs, RegionManager.PASSIVE_MOBS_DEFAULT));
        y += 22;
        addAggressivenessRow(y, viewportBottom, "Hostile Mobs", hostileMobsValue, v -> hostileMobsValue = v,
                RegionManager.get().effectiveAggressiveness(profileKey, finalRegion, r -> r.hostileMobs, RegionManager.HOSTILE_MOBS_DEFAULT));
        y += 22;
        addAggressivenessRow(y, viewportBottom, "Players", playersValue, v -> playersValue = v,
                RegionManager.get().effectiveAggressiveness(profileKey, finalRegion, r -> r.players, RegionManager.PLAYERS_DEFAULT));
        y += 30;

        if (rowVisible(y, 20, viewportBottom)) {
            addRenderableWidget(Button.builder(Component.literal("Save"), b -> onSave())
                    .bounds(10, y, 70, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                    .bounds(88, y, 70, 20).build());
        }

        // Total content height is a fixed function of this layout (same rows every rebuild), computed
        // as a byproduct of the pass above instead of a separately hand-maintained constant.
        int contentHeight = y + 20 - VIEWPORT_TOP + scrollOffset;
        maxScroll = Math.max(0, contentHeight - (viewportBottom - VIEWPORT_TOP));
    }

    /** Whether a row of height h at y is ENTIRELY within the visible scroll viewport -- a partially-cut-off row is skipped rather than clipped. */
    private static boolean rowVisible(int y, int h, int viewportBottom) {
        return y >= VIEWPORT_TOP && y + h <= viewportBottom;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll > 0) {
            int newOffset = ScrollState.scrolled(scrollOffset, maxScroll, scrollY, 15);
            if (newOffset != scrollOffset) {
                scrollOffset = newOffset;
                rebuildAllWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // "Inherit (X)" is shown only while the pick actually IS Inherit (null), where X is the value
    // resolved from the parent chain -- makes it obvious whether a row is explicit or inherited.
    private void addAggressivenessRow(int y, int viewportBottom, String label, Aggressiveness initial, java.util.function.Consumer<Aggressiveness> onChange, Aggressiveness effective) {
        if (!rowVisible(y, 20, viewportBottom)) return;
        addRenderableWidget(CycleButton.builder((Aggressiveness v) -> aggressivenessValue(v, effective), initial)
                .withValues(Arrays.asList(CYCLE))
                .create(10, y, 260, 20, Component.literal(label), (btn, value) -> onChange.accept(value)));
    }

    private static Component aggressivenessValue(Aggressiveness value, Aggressiveness effective) {
        return Component.literal(value == null ? "Inherit (" + prettify(effective) + ")" : prettify(value));
    }

    private static String prettify(Aggressiveness value) {
        return switch (value) {
            case OFF -> "Off";
            case REACTIVE -> "Reactive";
            case PROACTIVE -> "Proactive";
        };
    }

    private void addCheckbox(int y, int viewportBottom, String label, boolean selected, java.util.function.Consumer<Boolean> onChange) {
        Checkbox box = Checkbox.builder(Component.literal(label), font).pos(10, y).selected(selected)
                .onValueChange((cb, value) -> onChange.accept(value)).build();
        if (rowVisible(y, 20, viewportBottom)) addRenderableWidget(box);
    }

    private EditBox labeledBox(int y, int viewportBottom, String label, String value, java.util.function.Consumer<String> onChange) {
        return labeledBox(y, viewportBottom, label, value, 150, onChange);
    }

    private EditBox labeledBox(int y, int viewportBottom, String label, String value, int x, java.util.function.Consumer<String> onChange) {
        EditBox box = new EditBox(font, x, y, FIELD_W, FIELD_H, Component.literal(label));
        box.setHint(Component.literal(label));
        box.setValue(value);
        box.setMaxLength(64);
        box.setResponder(onChange::accept);
        if (rowVisible(y, FIELD_H, viewportBottom)) addRenderableWidget(box);
        return box;
    }

    private void smallBox(int y, int viewportBottom, int x, String value, String hint, java.util.function.Consumer<String> onChange) {
        EditBox box = new EditBox(font, x, y, 90, FIELD_H, Component.literal(hint));
        box.setHint(Component.literal(hint));
        box.setValue(value);
        box.setMaxLength(16);
        box.setResponder(onChange::accept);
        if (rowVisible(y, FIELD_H, viewportBottom)) addRenderableWidget(box);
    }

    private void onSave() {
        try {
            // "global" can't be renamed, and nothing else may be renamed INTO it either (nameBox is
            // also disabled for it above, but guard here too in case that ever changes).
            String newName = regionName.equals("global") ? "global" : nameText.trim();
            if (newName.isEmpty()) {
                statusLine = "Name can't be blank.";
                return;
            }
            if (!regionName.equals("global") && newName.equals("global")) {
                statusLine = "'global' is reserved for the implicit root region.";
                return;
            }

            RegionProfile profile = RegionManager.get().currentProfile();
            boolean renaming = !newName.equals(regionName);
            if (renaming && profile.regions.containsKey(newName)) {
                statusLine = "A region named '" + newName + "' already exists.";
                return;
            }

            String parent = parentText.trim();
            BlockPos a = new BlockPos(parseInt(minXText), parseInt(minYText), parseInt(minZText));
            BlockPos b = new BlockPos(parseInt(maxXText), parseInt(maxYText), parseInt(maxZText));

            Region region = profile.regions.get(regionName);
            if (region == null || region.isGlobal() && !regionName.equals("global")) {
                region = new Region();
                region.name = regionName;
                region.eventTasks = new LinkedHashMap<>();
                profile.regions.put(regionName, region);
            }
            if (renaming) {
                profile.regions.remove(regionName);
                // Every other region's parent pointer is a plain name lookup (RegionManager.walkFor*),
                // so it has to follow the rename or the inheritance chain silently breaks.
                for (Region r : profile.regions.values()) {
                    if (regionName.equals(r.parent)) r.parent = newName;
                }
                region.name = newName;
                profile.regions.put(newName, region);
            }
            region.parent = parent.isEmpty() ? null : parent;
            if (!newName.equals("global")) {
                region.setBounds(a, b);
            }
            region.excludeItemSources = excludeItemSourcesVal;
            region.excludeImplicitItemRetrieval = excludeImplicitRetrievalVal;
            region.excludeImplicitItemManufacturing = excludeImplicitManufacturingVal;
            region.passiveMobs = passiveMobsValue;
            region.hostileMobs = hostileMobsValue;
            region.players = playersValue;
            region.disableImplicitDestruction = disableImplicitDestructionVal;
            RegionManager.get().save();

            if (renaming) {
                // regionName is final; reopen fresh under the new name rather than patching it in place.
                Minecraft.getInstance().setScreen(new RegionEditScreen(newName));
            } else {
                statusLine = "Saved.";
            }
        } catch (NumberFormatException e) {
            statusLine = "Bounds must be whole numbers.";
        }
    }

    private static int parseInt(String text) {
        String v = text.trim();
        return v.isEmpty() ? 0 : Integer.parseInt(v);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xC0101010);
        g.text(font, getTitle().getString(), 10, 1, 0xFFFFFFFF);
        g.text(font, "profile: " + profileKey, 10, 12, 0xFF808080);
        if (maxScroll > 0) {
            g.text(font, "(scroll for more)", width - 90, 10, 0xFF808080);
        }
        g.text(font, statusLine, 10, height - 20, 0xFFAAAAAA);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
