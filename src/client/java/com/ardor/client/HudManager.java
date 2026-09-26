package com.ardor.client;

import com.ardor.config.ArdorConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
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
 * Reads go through the access-widened Hud/BossHealthOverlay fields (see ardor.accesswidener) --
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
        wrapVisibility(VanillaHudElements.OVERLAY_MESSAGE, () -> ArdorConfig.get().hudActionBarVisible && isNormalMode());
        wrapVisibility(VanillaHudElements.BOSS_BAR, () -> ArdorConfig.get().hudBossBarVisible && isNormalMode());
        wrapVisibility(VanillaHudElements.SCOREBOARD, () -> ArdorConfig.get().hudScoreboardVisible && isNormalMode());
        wrapVisibility(VanillaHudElements.TITLE_AND_SUBTITLE, () -> ArdorConfig.get().hudTitleVisible && isNormalMode());

        // "Replace the HUD entirely" while FLY or SIMS is active -- hide every other vanilla HUD
        // piece too (chat and the F3 debug overlay are deliberately left alone: chat is Ardor's one
        // sanctioned text channel per HudManager's own doc above, and F3 is a diagnostic the player
        // toggles themselves). FreecamOrchestratorOverlay (FLY) / the cursor + select-cube (SIMS)
        // draw the replacement content on top.
        for (Identifier id : List.of(
                VanillaHudElements.CROSSHAIR, VanillaHudElements.HOTBAR, VanillaHudElements.ARMOR_BAR,
                VanillaHudElements.HEALTH_BAR, VanillaHudElements.FOOD_BAR, VanillaHudElements.AIR_BAR,
                VanillaHudElements.MOUNT_HEALTH, VanillaHudElements.INFO_BAR, VanillaHudElements.EXPERIENCE_LEVEL,
                VanillaHudElements.HELD_ITEM_TOOLTIP, VanillaHudElements.MOB_EFFECTS, VanillaHudElements.SPECTATOR_TOOLTIP)) {
            wrapVisibility(id, HudManager::isNormalMode);
        }
    }

    private static boolean isNormalMode() {
        return CameraModeController.mode() == CameraModeController.Mode.NORMAL;
    }

    private static void wrapVisibility(Identifier id, BooleanSupplier visible) {
        HudElementRegistry.replaceElement(id, original -> (g, tracker) -> {
            if (visible.getAsBoolean()) original.extractRenderState(g, tracker);
        });
    }

    // ------------------------------------------------------------------ read

    public static String actionBarText() {
        Hud hud = Minecraft.getInstance().gui.hud;
        return hud.overlayMessageTime <= 0 ? null : hud.overlayMessageString.getString();
    }

    public static int actionBarTicksRemaining() {
        return Math.max(0, Minecraft.getInstance().gui.hud.overlayMessageTime);
    }

    public static List<BossBar> bossBars() {
        return Minecraft.getInstance().gui.hud.getBossOverlay().events.values().stream()
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
        Hud hud = Minecraft.getInstance().gui.hud;
        return hud.titleTime <= 0 || hud.title == null ? null : hud.title.getString();
    }

    public static String subtitleText() {
        Hud hud = Minecraft.getInstance().gui.hud;
        return hud.titleTime <= 0 || hud.subtitle == null ? null : hud.subtitle.getString();
    }

    /** Vanilla Hud.extractScoreboardSidebar's own resolution order: the player's team-color slot first, plain SIDEBAR as the fallback. */
    private static Objective sidebarObjective() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return null;
        Scoreboard scoreboard = mc.level.getScoreboard();
        PlayerTeam team = scoreboard.getPlayersTeam(mc.player.getScoreboardName());
        if (team != null && team.getColor().isPresent()) {
            Objective byTeamColor = scoreboard.getDisplayObjective(team.getColor().get().displaySlot());
            if (byTeamColor != null) return byTeamColor;
        }
        return scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
    }

    // ------------------------------------------------------------------ write
    //
    // No scoreboard/boss-bar writes: unlike the action bar and title, those aren't plain text fields
    // -- faking one means constructing the server-driven object graph (an Objective registered in
    // the synced Scoreboard, or a LerpingBossEvent keyed by UUID in BossHealthOverlay.events) that
    // the next real sync packet would then fight with. Out of scope, see TODO.md.
    //
    // "I never want to see text from ardor anywhere except the chat" -- setActionBarText/setTitle
    // used to actually show real vanilla action-bar/title text (indistinguishable from a server
    // packet doing the same). Both now print to chat instead, same [Ardor]-prefixed
    // addClientSystemMessage path StatusIndicator uses, regardless of what a script asked for.

    public static void setActionBarText(String text) {
        toChat(text);
    }

    public static void setTitle(String title, String subtitle) {
        toChat(subtitle == null || subtitle.isEmpty() ? title : title + " -- " + subtitle);
    }

    private static void toChat(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[Ardor] " + text));
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
        // Everything Ardor puts in chat carries the same [Ardor] prefix, this mirrored line
        // included -- append() keeps the original message's own formatting/color intact rather than
        // flattening it to plain text first.
        Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal("[Ardor] ").append(text));
    }
}
