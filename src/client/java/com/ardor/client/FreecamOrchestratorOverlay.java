package com.ardor.client;

import com.ardor.bridge.CompanionManagerClient;
import com.ardor.bridge.PeerDiscovery;
import com.ardor.config.ArdorConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The "Sims-like" replacement HUD shown only while FreecamController is active: one small clickable
 * card per known client, from THREE sources merged into one list --
 *   - "You" (this local instance), always first;
 *   - every hand-configured ArdorConfig peer, PLUS anything PeerDiscovery has heard a LAN beacon
 *     from that isn't already one of those (see PeerDiscovery's own doc -- discovery only ever
 *     finds an address, PeerClient still needs a matching peerSharedSecret to actually talk to it);
 *   - every instance the bedrock-bot companion is managing (Bedrock bots AND other Java clients it
 *     bridges to), via CompanionInstancePoller/CompanionManagerClient.
 * Click a card to select/deselect it (multi-select), then "Send Command" opens PeerCommandScreen,
 * which now also offers a Task Picker (saved scripts) alongside free text -- see that class.
 */
public final class FreecamOrchestratorOverlay {

    public enum Kind { LOCAL, PEER, COMPANION }

    /** host/port are only meaningful for Kind.PEER; companionInstance only for Kind.COMPANION. */
    public record Target(String key, String label, Kind kind, String host, int port,
                          CompanionManagerClient.ManagedInstance companionInstance) {}

    private static final int CARD_W = 100;
    private static final int CARD_H = 40;
    private static final int GAP = 6;

    private record Bounds(String key, int x0, int y0, int x1, int y1) {
        boolean contains(int mx, int my) {
            return mx >= x0 && mx < x1 && my >= y0 && my < y1;
        }
    }

    private static final Set<String> SELECTED = new LinkedHashSet<>();
    private static List<Target> lastTargets = List.of();
    private static List<Bounds> cardBounds = List.of();
    private static Bounds sendButtonBounds;

    private FreecamOrchestratorOverlay() {}

    public static void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("ardor", "freecam_orchestrator_overlay"),
                FreecamOrchestratorOverlay::render);
    }

    /**
     * Called from FreecamClickMixin on every real left-click while freecam is active and no screen is
     * open. Single-select by default -- a plain click replaces the whole selection with just the
     * clicked card (click it again to deselect). Shift-click toggles it into/out of a multi-selection
     * instead, for the "command several at once" case the ask also wanted -- without that modifier,
     * every click landing on a second card would otherwise silently grow a multi-selection nobody
     * asked for.
     */
    public static void handleClick(int mouseX, int mouseY, boolean multiSelect) {
        for (Bounds b : cardBounds) {
            if (b.contains(mouseX, mouseY)) {
                if (multiSelect) {
                    if (!SELECTED.add(b.key())) SELECTED.remove(b.key());
                } else {
                    boolean wasSoleSelection = SELECTED.size() == 1 && SELECTED.contains(b.key());
                    SELECTED.clear();
                    if (!wasSoleSelection) SELECTED.add(b.key());
                }
                return;
            }
        }
        if (sendButtonBounds != null && sendButtonBounds.contains(mouseX, mouseY) && !SELECTED.isEmpty()) {
            List<Target> targets = lastTargets.stream().filter(t -> SELECTED.contains(t.key())).toList();
            Minecraft.getInstance().gui.setScreen(new PeerCommandScreen(targets));
        }
    }

    private static List<Target> buildTargets() {
        List<Target> targets = new ArrayList<>();
        targets.add(new Target("local", "You", Kind.LOCAL, null, 0, null));

        Set<String> seenHostPort = new HashSet<>();
        for (ArdorConfig.PeerEntry peer : ArdorConfig.get().peers) {
            targets.add(new Target("peer:" + peer.name, peer.name, Kind.PEER, peer.host, peer.port, null));
            seenHostPort.add(peer.host + ":" + peer.port);
        }
        for (PeerDiscovery.Discovered d : PeerDiscovery.discovered()) {
            String hostPort = d.host() + ":" + d.port();
            if (!seenHostPort.add(hostPort)) continue; // already configured by hand -- don't show it twice
            targets.add(new Target("peer:" + hostPort, d.name() + " (found)", Kind.PEER, d.host(), d.port(), null));
        }
        for (CompanionManagerClient.ManagedInstance ci : CompanionInstancePoller.instances()) {
            targets.add(new Target("companion:" + ci.id(), ci.name() + " [" + ci.kind() + "]", Kind.COMPANION, null, 0, ci));
        }
        return targets;
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker tracker) {
        if (!FreecamController.isActive()) {
            cardBounds = List.of();
            sendButtonBounds = null;
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

        List<Target> targets = buildTargets();
        lastTargets = targets;
        Set<String> liveKeys = targets.stream().map(Target::key).collect(java.util.stream.Collectors.toSet());
        SELECTED.retainAll(liveKeys);

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int perRow = Math.max(1, (screenW - 20) / (CARD_W + GAP));
        int rows = (targets.size() + perRow - 1) / perRow;
        int totalW = Math.min(targets.size(), perRow) * CARD_W + (Math.min(targets.size(), perRow) - 1) * GAP;
        int startX = screenW / 2 - totalW / 2;
        int bottomY = screenH - 34 - rows * (CARD_H + GAP);

        List<Bounds> bounds = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            Target target = targets.get(i);
            int col = i % perRow;
            int row = i / perRow;
            int x = startX + col * (CARD_W + GAP);
            int y = bottomY + row * (CARD_H + GAP);
            boolean selected = SELECTED.contains(target.key());
            boolean reachable = target.kind() == Kind.LOCAL || isReachable(target);

            g.fill(x, y, x + CARD_W, y + CARD_H, selected ? 0xE0225588 : 0xC0101010);
            g.fill(x + 4, y + 4, x + 10, y + 10, reachable ? 0xFF33CC33 : 0xFF883333);
            g.text(font, trim(font, target.label(), CARD_W - 16), x + 14, y + 4, 0xFFFFFFFF);
            g.text(font, sourceLabel(target), x + 4, y + 20, 0xFFAAAAAA);

            bounds.add(new Bounds(target.key(), x, y, x + CARD_W, y + CARD_H));
        }
        cardBounds = bounds;

        int btnW = 200;
        int btnX = screenW / 2 - btnW / 2;
        int btnY = bottomY + rows * (CARD_H + GAP) + 2;
        String label = SELECTED.isEmpty() ? "Select a client above" : "Send Command (" + SELECTED.size() + ")";
        g.fill(btnX, btnY, btnX + btnW, btnY + 20, SELECTED.isEmpty() ? 0x80101010 : 0xE0225588);
        int textW = font.width(label);
        g.text(font, label, btnX + btnW / 2 - textW / 2, btnY + 6, 0xFFFFFFFF);
        sendButtonBounds = new Bounds("__send__", btnX, btnY, btnX + btnW, btnY + 20);

        String hint = "FREECAM (F6 to exit) -- click a card to select it, shift-click for several, then Send Command";
        g.text(font, hint, screenW / 2 - font.width(hint) / 2, 8, 0xFFFFFFFF);
    }

    private static boolean isReachable(Target target) {
        return switch (target.kind()) {
            case LOCAL -> true;
            case PEER -> PeerStatusPoller.isReachable(target.host(), target.port());
            case COMPANION -> !target.companionInstance().status().equals("stopped")
                    && !target.companionInstance().status().equals("error");
        };
    }

    private static String sourceLabel(Target target) {
        return switch (target.kind()) {
            case LOCAL -> "this client";
            case PEER -> target.host() + ":" + target.port();
            case COMPANION -> "companion: " + target.companionInstance().status();
        };
    }

    private static String trim(Font font, String text, int maxWidth) {
        return font.width(text) <= maxWidth ? text : font.plainSubstrByWidth(text, maxWidth - font.width("...")) + "...";
    }
}
