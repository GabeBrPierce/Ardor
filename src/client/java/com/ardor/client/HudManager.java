package com.ardor.client;

import com.ardor.config.ArdorConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Read, write, and hide the four server-driven vanilla HUD elements: the action bar, boss bars, the
 * scoreboard sidebar, and title/subtitle. Backs the Lua HudManager table.
 *
 * Reads go through the access-widened Gui/BossHealthOverlay fields (see ardor.accesswidener) --
 * vanilla exposes setters but no getters for any of this. Writes use the real public setters, so
 * they behave identically to a server-sent ActionBar/Title packet.
 *
 * Hiding is done with HudElementRegistry.replaceElement rather than removeElement: the wrapper
 * registered once in register() re-reads its ArdorConfig flag every frame, so the toggles below are
 * just config writes and take effect on the next frame with nothing to re-register.
 */
public final class HudManager {

    public record BossBar(String name, float progress, String color) {}

    public record ScoreEntry(String name, int score) {}

    private HudManager() {}

    public static void register() {
        wrapVisibility(VanillaHudElements.OVERLAY_MESSAGE, () -> ArdorConfig.get().hudActionBarVisible);
        wrapVisibility(VanillaHudElements.BOSS_BAR, () -> ArdorConfig.get().hudBossBarVisible);
        wrapVisibility(VanillaHudElements.SCOREBOARD, () -> ArdorConfig.get().hudScoreboardVisible);
        wrapVisibility(VanillaHudElements.TITLE_AND_SUBTITLE, () -> ArdorConfig.get().hudTitleVisible);
    }

    private static void wrapVisibility(Identifier id, BooleanSupplier visible) {
        HudElementRegistry.replaceElement(id, original -> (g, tracker) -> {
            if (visible.getAsBoolean()) original.extractRenderState(g, tracker);
        });
    }

    // ------------------------------------------------------------------ read

    public static String actionBarText() {
        Gui gui = Minecraft.getInstance().gui;
        return gui.overlayMessageTime <= 0 ? null : gui.overlayMessageString.getString();
    }

    public static int actionBarTicksRemaining() {
        return Math.max(0, Minecraft.getInstance().gui.overlayMessageTime);
    }

    public static List<BossBar> bossBars() {
        return Minecraft.getInstance().gui.getBossOverlay().events.values().stream()
                .map(e -> new BossBar(e.getName().getString(), e.getProgress(), e.getColor().name()))
                .toList();
    }

    public static String scoreboardTitle() {
        Objective objective = sidebarObjective();
        return objective == null ? null : objective.getDisplayName().getString();
    }

    public static List<ScoreEntry> scoreboardEntries() {
        Objective objective = sidebarObjective();
        if (objective == null) return List.of();
        List<ScoreEntry> entries = new ArrayList<>();
        for (var entry : Minecraft.getInstance().level.getScoreboard().listPlayerScores(objective)) {
            if (!entry.isHidden()) entries.add(new ScoreEntry(entry.display().getString(), entry.value()));
        }
        return entries;
    }

    public static String titleText() {
        Gui gui = Minecraft.getInstance().gui;
        return gui.titleTime <= 0 || gui.title == null ? null : gui.title.getString();
    }

    public static String subtitleText() {
        Gui gui = Minecraft.getInstance().gui;
        return gui.titleTime <= 0 || gui.subtitle == null ? null : gui.subtitle.getString();
    }

    /** Vanilla Gui.extractScoreboardSidebar's own resolution order: the player's team-color slot first, plain SIDEBAR as the fallback. */
    private static Objective sidebarObjective() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return null;
        Scoreboard scoreboard = mc.level.getScoreboard();
        PlayerTeam team = scoreboard.getPlayersTeam(mc.player.getScoreboardName());
        if (team != null) {
            DisplaySlot slot = DisplaySlot.teamColorToSlot(team.getColor());
            if (slot != null) {
                Objective byTeamColor = scoreboard.getDisplayObjective(slot);
                if (byTeamColor != null) return byTeamColor;
            }
        }
        return scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
    }

    // ------------------------------------------------------------------ write
    //
    // No scoreboard/boss-bar writes: unlike the action bar and title, those aren't plain text fields
    // -- faking one means constructing the server-driven object graph (an Objective registered in
    // the synced Scoreboard, or a LerpingBossEvent keyed by UUID in BossHealthOverlay.events) that
    // the next real sync packet would then fight with. Out of scope, see TODO.md.

    public static void setActionBarText(String text) {
        Minecraft.getInstance().gui.setOverlayMessage(Component.literal(text), false);
    }

    public static void setTitle(String title, String subtitle) {
        Gui gui = Minecraft.getInstance().gui;
        gui.setSubtitle(Component.literal(subtitle));
        gui.setTitle(Component.literal(title));
    }

    // ------------------------------------------------------------------ visibility

    public static void setActionBarVisible(boolean visible) {
        ArdorConfig.get().hudActionBarVisible = visible;
        ArdorConfig.get().save();
    }

    public static void setBossBarVisible(boolean visible) {
        ArdorConfig.get().hudBossBarVisible = visible;
        ArdorConfig.get().save();
    }

    public static void setScoreboardVisible(boolean visible) {
        ArdorConfig.get().hudScoreboardVisible = visible;
        ArdorConfig.get().save();
    }

    public static void setTitleVisible(boolean visible) {
        ArdorConfig.get().hudTitleVisible = visible;
        ArdorConfig.get().save();
    }

    // ------------------------------------------------------------------ chat mirror

    /**
     * OverlayMessageMirrorMixin's hook on Gui.setOverlayMessage -- the one funnel every action-bar
     * message goes through (server ActionBar packets via ChatListener.handleOverlay,
     * Player.sendOverlayMessage, this mod's own writes). Purely local: addClientSystemMessage is
     * vanilla's own client-generated-message path into the chat log, no packet is sent.
     */
    public static void onOverlayMessageSet(Component text) {
        if (!ArdorConfig.get().hudMirrorActionBarToChat) return;
        Minecraft.getInstance().gui.getChat().addClientSystemMessage(text);
    }
}
