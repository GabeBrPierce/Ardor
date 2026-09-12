package com.ardor.client;

import com.ardor.container.ContainerSource;
import com.ardor.container.ContentsEntry;
import com.ardor.container.CreativeCategories;
import com.ardor.container.SourceManager;
import com.ardor.container.SourceType;
import com.ardor.container.SubKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * "Add New" modal, built as its own Screen matching this codebase's modal-as-screen convention
 * (RegionEditScreen reached from RegionListScreen), not a true in-screen popup. sourceId null =
 * creating a new source; non-null = editing an existing one. Desired Contents (Physical/Sub
 * container) / Expected Contents (Command) is declarative metadata for search/display only (see
 * ContentsEntry) -- Save never blocks on it being filled in.
 */
public final class SourceEditScreen extends Screen {

    private enum ContentsTab { CATEGORIES, ITEMS }

    private final ContainerSource existing;
    private SourceType type;
    private SubKind subKind;
    private boolean parentByName = true;
    private ContentsTab contentsTab = ContentsTab.CATEGORIES;
    private final List<ContentsEntry> desiredContents = new ArrayList<>();

    private EditBox nameBox;
    private EditBox xBox, yBox, zBox;
    private EditBox parentNameBox, parentSlotBox;
    private EditBox parentXBox, parentYBox, parentZBox;
    private EditBox commandBox;
    private EditBox cooldownBox;
    private CycleButton<String> categoryPicker;
    private EditBox itemIdBox, customNameBox, deltaBox;

    private int listTop;
    private String statusLine = "";

    public SourceEditScreen(String sourceId) {
        super(Component.literal(sourceId == null ? "Add New Source" : "Edit Source"));
        this.existing = sourceId != null ? SourceManager.get().get(sourceId) : null;
        this.type = existing != null ? existing.type : SourceType.PHYSICAL;
        this.subKind = existing != null && existing.subKind != null ? existing.subKind : SubKind.SHULKER;
        if (existing != null) {
            desiredContents.addAll(existing.desiredContents);
            parentByName = existing.parentSourceId != null || existing.parentX == null;
        }
    }

    @Override
    protected void init() {
        rebuildAllWidgets();
    }

    private void rebuildAllWidgets() {
        clearWidgets();

        CycleButton<SourceType> typeButton = addRenderableWidget(CycleButton.builder(
                        (SourceType t) -> Component.literal(typeLabel(t)), type)
                .withValues(List.of(SourceType.PHYSICAL, SourceType.SUBCONTAINER, SourceType.COMMAND))
                .create(10, 10, 200, 20, Component.literal("Type"), (btn, value) -> { type = value; rebuildAllWidgets(); }));
        typeButton.active = existing == null; // an existing source's type is fixed, same as a region's name

        int y = 40;

        if (type != SourceType.COMMAND) {
            nameBox = new EditBox(font, 10, y, 300, 20, Component.literal("Name"));
            nameBox.setMaxLength(64);
            nameBox.setValue(existing != null && existing.name != null ? existing.name : "");
            addRenderableWidget(nameBox);
            y += 26;
        }

        if (type == SourceType.PHYSICAL || type == SourceType.CAULDRON) {
            xBox = smallBox(10, y, existing != null && existing.x != null ? String.valueOf(existing.x) : "");
            yBox = smallBox(80, y, existing != null && existing.y != null ? String.valueOf(existing.y) : "");
            zBox = smallBox(150, y, existing != null && existing.z != null ? String.valueOf(existing.z) : "");
            addRenderableWidget(Button.builder(Component.literal("Use my position"), b -> useMyPosition())
                    .bounds(230, y, 130, 20).build());
            y += 26;
        } else if (type == SourceType.SUBCONTAINER) {
            addRenderableWidget(CycleButton.builder((SubKind k) -> Component.literal(k == SubKind.SHULKER ? "Shulker Box" : "Bundle"), subKind)
                    .withValues(List.of(SubKind.SHULKER, SubKind.BUNDLE))
                    .create(10, y, 160, 20, Component.literal("Kind"), (btn, value) -> subKind = value));
            y += 26;

            addRenderableWidget(CycleButton.builder((String s) -> Component.literal(s), parentByName ? "Parent Name" : "Parent Location")
                    .withValues(List.of("Parent Name", "Parent Location"))
                    .create(10, y, 160, 20, Component.literal("Parent by"), (btn, value) -> { parentByName = value.equals("Parent Name"); rebuildAllWidgets(); }));
            y += 26;

            if (parentByName) {
                parentNameBox = new EditBox(font, 10, y, 220, 20, Component.literal("Parent source name"));
                parentNameBox.setMaxLength(64);
                parentNameBox.setValue(existing != null && existing.parentSourceId != null ? parentSourceLabel(existing.parentSourceId) : "");
                addRenderableWidget(parentNameBox);
            } else {
                parentXBox = smallBox(10, y, existing != null && existing.parentX != null ? String.valueOf(existing.parentX) : "");
                parentYBox = smallBox(80, y, existing != null && existing.parentY != null ? String.valueOf(existing.parentY) : "");
                parentZBox = smallBox(150, y, existing != null && existing.parentZ != null ? String.valueOf(existing.parentZ) : "");
            }
            y += 26;

            parentSlotBox = smallBox(10, y, existing != null && existing.parentSlot != null ? String.valueOf(existing.parentSlot) : "0");
            y += 26;
        } else {
            commandBox = new EditBox(font, 10, y, 400, 20, Component.literal("Command"));
            commandBox.setMaxLength(300);
            commandBox.setValue(existing != null && existing.command != null ? existing.command : "");
            commandBox.setHint(Component.literal("/kit food"));
            addRenderableWidget(commandBox);
            y += 26;

            cooldownBox = new EditBox(font, 10, y, 100, 20, Component.literal("Cooldown (seconds)"));
            cooldownBox.setMaxLength(8);
            cooldownBox.setValue(existing != null && existing.cooldownSeconds != null ? String.valueOf(existing.cooldownSeconds) : "");
            cooldownBox.setHint(Component.literal("Cooldown (s)"));
            addRenderableWidget(cooldownBox);
            y += 26;
        }

        y += 10; // gap before the Desired/Expected Contents editor
        int contentsHeaderY = y;
        y += 16;

        addRenderableWidget(CycleButton.builder((ContentsTab t) -> Component.literal(t == ContentsTab.CATEGORIES ? "Categories" : "Specific items"), contentsTab)
                .withValues(List.of(ContentsTab.CATEGORIES, ContentsTab.ITEMS))
                .create(10, y, 160, 20, Component.literal("Tab"), (btn, value) -> { contentsTab = value; rebuildAllWidgets(); }));
        y += 26;

        if (contentsTab == ContentsTab.CATEGORIES) {
            List<String> categoryNames = new ArrayList<>(CreativeCategories.categories().keySet());
            if (categoryNames.isEmpty()) categoryNames.add("(no categories found)");
            categoryPicker = addRenderableWidget(CycleButton.builder((String s) -> Component.literal(s), categoryNames.get(0))
                    .withValues(categoryNames)
                    .create(10, y, 240, 20, Component.literal("Category")));
            addRenderableWidget(Button.builder(Component.literal("Add Category"), b -> addCategoryEntry())
                    .bounds(260, y, 120, 20).build());
        } else {
            itemIdBox = new EditBox(font, 10, y, 160, 20, Component.literal("Item id"));
            itemIdBox.setMaxLength(120);
            itemIdBox.setHint(Component.literal("minecraft:steak"));
            addRenderableWidget(itemIdBox);

            customNameBox = new EditBox(font, 180, y, 140, 20, Component.literal("Custom name contains"));
            customNameBox.setMaxLength(120);
            addRenderableWidget(customNameBox);

            int addX = 330;
            if (type == SourceType.COMMAND) {
                deltaBox = new EditBox(font, 330, y, 50, 20, Component.literal("+N"));
                deltaBox.setMaxLength(6);
                deltaBox.setValue("1");
                addRenderableWidget(deltaBox);
                addX = 390;
            }
            addRenderableWidget(Button.builder(Component.literal("Add Item"), b -> addItemEntry())
                    .bounds(addX, y, 100, 20).build());
        }
        y += 26;

        listTop = y;
        int rowY = listTop;
        for (int i = 0; i < desiredContents.size(); i++) {
            int index = i;
            addRenderableWidget(Button.builder(Component.literal("X"), b -> { desiredContents.remove(index); rebuildAllWidgets(); })
                    .bounds(10, rowY, 18, 16).build());
            rowY += 18;
        }

        int bottomY = Math.max(rowY + 12, height - 30);
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> onSave())
                .bounds(10, bottomY, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(88, bottomY, 70, 20).build());

        this.contentsHeaderY = contentsHeaderY;
    }

    private int contentsHeaderY;

    private EditBox smallBox(int x, int y, String value) {
        EditBox box = new EditBox(font, x, y, 60, 20, Component.literal(""));
        box.setMaxLength(12);
        box.setValue(value);
        return addRenderableWidget(box);
    }

    private void useMyPosition() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        BlockPos pos = player.blockPosition();
        xBox.setValue(String.valueOf(pos.getX()));
        yBox.setValue(String.valueOf(pos.getY()));
        zBox.setValue(String.valueOf(pos.getZ()));
    }

    private void addCategoryEntry() {
        if (categoryPicker == null) return;
        ContentsEntry e = new ContentsEntry();
        e.kind = ContentsEntry.Kind.CATEGORY;
        e.category = categoryPicker.getValue();
        desiredContents.add(e);
        rebuildAllWidgets();
    }

    private void addItemEntry() {
        String itemId = itemIdBox.getValue().trim();
        if (itemId.isEmpty()) {
            statusLine = "Type an item id first.";
            return;
        }
        ContentsEntry e = new ContentsEntry();
        e.kind = ContentsEntry.Kind.ITEM;
        e.itemId = itemId;
        String customName = customNameBox.getValue().trim();
        if (!customName.isEmpty()) e.customNameMatch = customName;
        if (deltaBox != null) {
            try {
                e.delta = Integer.parseInt(deltaBox.getValue().trim());
            } catch (NumberFormatException ex) {
                e.delta = 0;
            }
        }
        desiredContents.add(e);
        itemIdBox.setValue("");
        customNameBox.setValue("");
        rebuildAllWidgets();
    }

    private void onSave() {
        try {
            ContainerSource s = existing != null ? existing : new ContainerSource();
            s.type = type;
            s.desiredContents = new ArrayList<>(desiredContents);

            if (type != SourceType.COMMAND) {
                String name = nameBox.getValue().trim();
                s.name = name.isEmpty() ? null : name;
            }

            if (type == SourceType.PHYSICAL || type == SourceType.CAULDRON) {
                s.x = parseIntOrThrow(xBox, "X");
                s.y = parseIntOrThrow(yBox, "Y");
                s.z = parseIntOrThrow(zBox, "Z");
            } else if (type == SourceType.SUBCONTAINER) {
                s.subKind = subKind;
                s.parentSlot = parseIntOrThrow(parentSlotBox, "parent slot");
                if (parentByName) {
                    String parentName = parentNameBox.getValue().trim();
                    ContainerSource parent = findSourceByName(parentName);
                    if (parent == null) {
                        statusLine = "No source named '" + parentName + "'.";
                        return;
                    }
                    s.parentSourceId = parent.id;
                    s.parentX = null; s.parentY = null; s.parentZ = null;
                } else {
                    s.parentX = parseIntOrThrow(parentXBox, "parent X");
                    s.parentY = parseIntOrThrow(parentYBox, "parent Y");
                    s.parentZ = parseIntOrThrow(parentZBox, "parent Z");
                    s.parentSourceId = null;
                }
            } else {
                String command = commandBox.getValue().trim();
                if (command.isEmpty()) {
                    statusLine = "Type a command first.";
                    return;
                }
                s.command = command;
                String cooldown = cooldownBox.getValue().trim();
                if (cooldown.isEmpty()) {
                    s.cooldownSeconds = null;
                } else {
                    try {
                        s.cooldownSeconds = Integer.parseInt(cooldown);
                    } catch (NumberFormatException e) {
                        statusLine = "Cooldown must be a whole number of seconds.";
                        return;
                    }
                }
            }

            if (existing != null) {
                SourceManager.get().update(s);
            } else {
                SourceManager.get().add(s);
            }
            statusLine = "Saved.";
        } catch (NumberFormatException e) {
            statusLine = e.getMessage();
        }
    }

    private int parseIntOrThrow(EditBox box, String fieldLabel) {
        String v = box.getValue().trim();
        if (v.isEmpty()) throw new NumberFormatException(fieldLabel + " is required.");
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new NumberFormatException(fieldLabel + " must be a whole number.");
        }
    }

    private ContainerSource findSourceByName(String name) {
        for (ContainerSource s : SourceManager.get().currentProfile().sources.values()) {
            if (s.name != null && s.name.equalsIgnoreCase(name)) return s;
        }
        return null;
    }

    private String parentSourceLabel(String parentSourceId) {
        ContainerSource parent = SourceManager.get().get(parentSourceId);
        return parent != null && parent.name != null ? parent.name : "";
    }

    private String typeLabel(SourceType t) {
        return switch (t) {
            case PHYSICAL -> "Physical Placed Container";
            case SUBCONTAINER -> "Sub Container";
            case COMMAND -> "Command";
            case ENDER_CHEST -> "Ender Chest";
            case CAULDRON -> "Cauldron";
        };
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xC0101010);

        String contentsLabel = type == SourceType.COMMAND ? "Expected Contents:" : "Desired Contents:";
        g.text(font, contentsLabel, 10, contentsHeaderY, 0xFFFFFFFF);

        int rowY = listTop;
        for (ContentsEntry e : desiredContents) {
            g.text(font, describeEntry(e), 32, rowY + 3, 0xFFCCCCCC);
            rowY += 18;
        }

        g.text(font, statusLine, 10, height - 12, 0xFFAAAAAA);

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private String describeEntry(ContentsEntry e) {
        if (e.kind == ContentsEntry.Kind.CATEGORY) return "category: " + e.category;
        String line = "item: " + e.itemId;
        if (e.customNameMatch != null) line += " (named \"" + e.customNameMatch + "\")";
        if (e.delta != 0) line += (e.delta > 0 ? " +" : " ") + e.delta;
        return line;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
