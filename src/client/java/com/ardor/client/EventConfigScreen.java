package com.ardor.client;

import com.ardor.event.FabricEventCatalog;
import com.ardor.region.Region;
import com.ardor.region.RegionManager;
import com.ardor.region.RegionProfile;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Region dropdown (applies to the current profile, RegionManager.currentProfileKey()
 * -- "the region should apply to the lowest sub-region the player is in" is
 * how EventHookDispatcher RESOLVES a bound task at fire time, not something
 * chosen here; here you pick which region a binding is stored ON) + event
 * dropdown (FabricEventCatalog.allEventIds() -- domain events first, then
 * every real Fabric event found this session, wired or not) + a task text
 * box, with Bind/Remove and a live list of the selected region's existing
 * bindings.
 */
public final class EventConfigScreen extends Screen {

    private CycleButton<String> regionButton;
    private CycleButton<String> eventButton;
    private EditBox taskBox;
    private EditBox phraseBox;
    private EditBox phraseTaskBox;
    private String statusLine = "";

    public EventConfigScreen() {
        super(Component.literal("Ardor Events"));
    }

    @Override
    protected void init() {
        clearWidgets();
        RegionProfile profile = RegionManager.get().currentProfile();
        List<String> regionNames = new ArrayList<>(profile.regions.keySet());
        if (!regionNames.contains("global")) regionNames.add(0, "global");

        regionButton = addRenderableWidget(CycleButton.builder((String s) -> Component.literal(s), regionNames.get(0))
                .withValues(regionNames)
                .create(10, 10, 150, 20, Component.literal("Region")));

        List<String> eventIds = FabricEventCatalog.allEventIds();
        eventButton = addRenderableWidget(CycleButton.builder(
                        (String s) -> Component.literal((FabricEventCatalog.isWired(s) ? "" : "(unwired) ") + s), eventIds.get(0))
                .withValues(eventIds)
                .create(165, 10, 260, 20, Component.literal("Event")));

        taskBox = new EditBox(font, 10, 36, 415, 20, Component.literal("Task"));
        taskBox.setMaxLength(500);
        addRenderableWidget(taskBox);

        addRenderableWidget(Button.builder(Component.literal("Bind"), b -> onBind())
                .bounds(10, 62, 80, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Remove"), b -> onRemove())
                .bounds(96, 62, 80, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 65, 62, 55, 20).build());

        // "React to chat phrase" -- a separate row, since a phrase binding needs a parameter
        // (the phrase itself) that a single dropdown entry can't carry -- see
        // RegionManager.CHAT_PHRASE_PREFIX/bindChatPhrase.
        phraseBox = new EditBox(font, 10, 96, 150, 20, Component.literal("Phrase"));
        phraseBox.setMaxLength(200);
        addRenderableWidget(phraseBox);
        phraseTaskBox = new EditBox(font, 165, 96, 260, 20, Component.literal("Response task"));
        phraseTaskBox.setMaxLength(500);
        addRenderableWidget(phraseTaskBox);
        addRenderableWidget(Button.builder(Component.literal("Bind Phrase"), b -> onBindPhrase())
                .bounds(10, 120, 110, 20).build());
    }

    private void onBindPhrase() {
        String phrase = phraseBox.getValue().trim();
        String task = phraseTaskBox.getValue().trim();
        if (phrase.isEmpty() || task.isEmpty()) {
            statusLine = "Type both a phrase and a response task.";
            return;
        }
        RegionManager.get().bindChatPhrase(RegionManager.currentProfileKey(), regionButton.getValue(), phrase, task);
        statusLine = "Bound chat phrase \"" + phrase + "\" -> \"" + task + "\" on region '" + regionButton.getValue() + "'.";
    }

    private void onBind() {
        String regionName = regionButton.getValue();
        String eventId = eventButton.getValue();
        String task = taskBox.getValue().trim();
        if (task.isEmpty()) {
            statusLine = "Type a task first.";
            return;
        }
        Region region = regionOrCreate(regionName);
        region.eventTasks.put(eventId, task);
        RegionManager.get().save();
        statusLine = "Bound " + eventId + " -> \"" + task + "\" on region '" + regionName + "'.";
    }

    private void onRemove() {
        String regionName = regionButton.getValue();
        String eventId = eventButton.getValue();
        Region region = RegionManager.get().currentProfile().regions.get(regionName);
        if (region != null && region.eventTasks.remove(eventId) != null) {
            RegionManager.get().save();
            statusLine = "Removed " + eventId + " from region '" + regionName + "'.";
        } else {
            statusLine = "No binding for " + eventId + " on '" + regionName + "'.";
        }
    }

    private Region regionOrCreate(String name) {
        RegionProfile profile = RegionManager.get().currentProfile();
        return profile.regions.computeIfAbsent(name, n -> {
            Region r = new Region();
            r.name = n;
            return r;
        });
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xC0101010);

        g.text(font, statusLine, 10, 146, 0xFFAAAAAA);

        Region region = RegionManager.get().currentProfile().regions.get(regionButton.getValue());
        int y = 164;
        g.text(font, "Bindings on '" + regionButton.getValue() + "':", 10, y, 0xFFFFFFFF);
        y += 12;
        if (region == null || region.eventTasks.isEmpty()) {
            g.text(font, "(none)", 20, y, 0xFF808080);
        } else {
            for (Map.Entry<String, String> e : region.eventTasks.entrySet()) {
                g.text(font, e.getKey() + " -> " + e.getValue(), 20, y, 0xFFCCCCCC);
                y += 12;
            }
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
