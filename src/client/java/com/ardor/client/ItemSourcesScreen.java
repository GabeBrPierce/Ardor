package com.ardor.client;

import com.ardor.container.ContainerSource;
import com.ardor.container.SourceManager;
import com.ardor.container.SourceType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists every item source in the current profile: Name (or location -- see
 * ContainerSource.displayLabel), Type, an Enable checkbox (default checked), Edit and Remove
 * buttons per row -- exactly the row shape the spec calls for. Search filters live via
 * EditBox.setResponder against the row's display label and type, same live-filter convention
 * FetchItemsScreen uses. Same "clear and rebuild every widget, including the fixed header ones,
 * whenever the dynamic list changes" pattern TaskPlannerScreen already uses for its own
 * reorderable task rows -- simplest way to keep a variable-length widget list in sync here too.
 */
public final class ItemSourcesScreen extends Screen {

    private static final int ROW_TOP = 40;
    private static final int ROW_H = 20;

    private static String searchText = "";

    private EditBox searchBox;
    private List<ContainerSource> visible = List.of();

    public ItemSourcesScreen() {
        super(Component.literal("Item Sources"));
    }

    @Override
    protected void init() {
        rebuildAllWidgets();
    }

    private void rebuildAllWidgets() {
        clearWidgets();

        searchBox = new EditBox(font, 10, 10, 300, 20, Component.literal("Search"));
        searchBox.setMaxLength(200);
        searchBox.setValue(searchText);
        searchBox.setResponder(v -> { searchText = v; rebuildAllWidgets(); });
        addRenderableWidget(searchBox);
        setInitialFocus(searchBox);

        addRenderableWidget(Button.builder(Component.literal("Add New"), b -> Minecraft.getInstance().setScreen(new SourceEditScreen(null)))
                .bounds(320, 10, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Fetch Items"), b -> Minecraft.getInstance().setScreen(new FetchItemsScreen()))
                .bounds(416, 10, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 65, 10, 55, 20).build());

        String q = searchText.trim().toLowerCase();
        List<ContainerSource> all = new ArrayList<>(SourceManager.get().currentProfile().sources.values());
        visible = all.stream()
                .filter(s -> q.isEmpty()
                        || s.displayLabel(SourceManager.get()).toLowerCase().contains(q)
                        || typeLabel(s.type).toLowerCase().contains(q))
                .toList();

        int y = ROW_TOP;
        for (ContainerSource source : visible) {
            boolean isEnderChest = source.type == SourceType.ENDER_CHEST;

            addRenderableWidget(Checkbox.builder(Component.literal("Enable"), font)
                    .pos(300, y)
                    .selected(source.enabled)
                    .onValueChange((cb, value) -> SourceManager.get().setEnabled(source.id, value))
                    .build());

            addRenderableWidget(Button.builder(Component.literal("Edit"), b -> Minecraft.getInstance().setScreen(new SourceEditScreen(source.id)))
                    .bounds(390, y, 55, ROW_H - 2).build());

            Button remove = addRenderableWidget(Button.builder(Component.literal("Remove"), b -> {
                try {
                    SourceManager.get().remove(source.id);
                } catch (RuntimeException ignored) { /* the built-in ender chest source refuses removal */ }
                rebuildAllWidgets();
            }).bounds(450, y, 60, ROW_H - 2).build());
            remove.active = !isEnderChest;

            y += ROW_H;
        }
    }

    private String typeLabel(SourceType type) {
        return switch (type) {
            case PHYSICAL -> "Physical Container";
            case SUBCONTAINER -> "Sub Container";
            case ENDER_CHEST -> "Ender Chest";
            case COMMAND -> "Command";
            case CAULDRON -> "Cauldron";
        };
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xC0101010);

        int y = ROW_TOP;
        for (ContainerSource source : visible) {
            g.text(font, source.displayLabel(SourceManager.get()), 10, y + 5, 0xFFFFFFFF);
            g.text(font, typeLabel(source.type), 150, y + 5, 0xFFAAAAAA);
            y += ROW_H;
        }
        if (visible.isEmpty()) {
            g.text(font, "No sources yet -- Add New to create one.", 10, ROW_TOP, 0xFF808080);
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
