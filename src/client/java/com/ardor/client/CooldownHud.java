package com.ardor.client;

import com.ardor.script.ScriptEngine;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Top-right depleting progress bar per bar-enabled ScriptEngine.startCooldown, label above the bar
 * -- replaces the old once-a-second StatusIndicator chat spam. Multiple concurrent bars stack
 * downward in ScriptEngine.activeCooldownBars()'s (start) order.
 */
public final class CooldownHud {

    private static final int BAR_WIDTH = 120;
    private static final int BAR_HEIGHT = 6;
    private static final int MARGIN = 8;
    private static final int ENTRY_GAP = 4;

    private CooldownHud() {}

    public static void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("ardor", "cooldown_bars"), (g, tracker) -> {
            List<ScriptEngine.CooldownBar> bars = ScriptEngine.activeCooldownBars();
            if (bars.isEmpty()) return;

            Font font = Minecraft.getInstance().font;
            int x = g.guiWidth() - MARGIN - BAR_WIDTH;
            int y = MARGIN;
            for (ScriptEngine.CooldownBar bar : bars) {
                int labelWidth = font.width(bar.label());
                g.text(font, bar.label(), g.guiWidth() - MARGIN - labelWidth, y, 0xFFFFFFFF);
                y += font.lineHeight + 1;

                float frac = bar.total() <= 0 ? 0f : Math.max(0f, Math.min(1f, bar.remaining() / (float) bar.total()));
                g.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, 0x80000000);
                int filled = Math.round(BAR_WIDTH * frac);
                if (filled > 0) g.fill(x, y, x + filled, y + BAR_HEIGHT, 0xFFFFD700);
                y += BAR_HEIGHT + ENTRY_GAP;
            }
        });
    }
}
