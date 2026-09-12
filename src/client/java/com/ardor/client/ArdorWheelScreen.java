package com.ardor.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * A generic radial menu: hold middle-click to show, move the mouse toward an option, release to
 * confirm (release near center to cancel) -- see PickWheelKey for how it's opened. Used both for
 * the top-level wheel (mainWheel(): Single Selection / Area Selection / Pick Block) and for
 * SingleSelectionMode's per-target sub-wheels (e.g. the entity sub-wheel: follow/kill/kill all/
 * defend) -- "middle-click again" opens a SECOND wheel of just those options with the exact same
 * hold-to-open/release-to-confirm gesture, rather than a flat list or cycling through taps (see
 * TODO.md's Ardor Wheel Phase 2 entry for that design decision). A single-option target (today:
 * Go Here) stays a plain tap instead -- there's nothing to choose between.
 *
 * Rendered with plain filled rects (GuiGraphicsExtractor has no polygon/triangle-fan primitive --
 * confirmed via javap against the real client jar, only axis-aligned fill/gradient/blit), same
 * style as every other screen in this codebase; it's the angle-based hover/selection that makes
 * this a wheel rather than a list, not literal pie-slice geometry.
 *
 * Picking Area Selection opens a Radius/Corners wheel (AreaSelectionMode.startRadius()/
 * startCorners()), and either of those eventually opens ITS OWN follow-up wheel (Set As Region /
 * Break Blocks Within / Kill Hostile Mobs) once an area is captured -- mouseReleased only closes
 * whichever wheel is still on screen after its action runs, so this chaining doesn't clobber
 * itself. See TODO.md's Ardor Wheel Phase 4 entry.
 */
public final class ArdorWheelScreen extends Screen {

    /** One wheel wedge: a label and what happens when it's picked. */
    public record WheelOption(String label, Runnable action) {}

    private static final int RADIUS = 70;
    private static final int OPTION_W = 100;
    private static final int OPTION_H = 30;
    private static final int DEAD_ZONE = 18; // release within this radius of center = cancel

    private final List<WheelOption> options;

    public ArdorWheelScreen(List<WheelOption> options) {
        super(Component.literal("Ardor"));
        this.options = options;
    }

    /** The top-level wheel PickWheelKey opens on hold when there's no more specific sub-wheel for the current target. */
    public static ArdorWheelScreen mainWheel() {
        return new ArdorWheelScreen(List.of(
                new WheelOption("Single Selection", SingleSelectionMode::toggle),
                new WheelOption("Area Selection", () -> Minecraft.getInstance().setScreen(new ArdorWheelScreen(List.of(
                        new WheelOption("Radius", AreaSelectionMode::startRadius),
                        new WheelOption("Corners", AreaSelectionMode::startCorners)
                )))),
                new WheelOption("Pick Block", () -> Minecraft.getInstance().pickBlockOrEntity())
        ));
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 2) {
            Integer index = hoveredIndex(event.x(), event.y());
            if (index != null) options.get(index).action().run();
            // An action may have already opened a different screen (e.g. Area Selection opening
            // the Radius/Corners wheel, or a follow-up wheel) -- only close if this wheel is still
            // the one showing, so that chain isn't immediately clobbered.
            if (Minecraft.getInstance().screen == this) {
                Minecraft.getInstance().setScreen(null);
            }
            return true;
        }
        return super.mouseReleased(event);
    }

    /** Which option's wedge the given point falls in, by angle from center (clockwise from up), or null if inside the dead zone. */
    private Integer hoveredIndex(double mouseX, double mouseY) {
        double dx = mouseX - width / 2.0;
        double dy = mouseY - height / 2.0;
        if (Math.sqrt(dx * dx + dy * dy) < DEAD_ZONE) return null;

        double angle = Math.toDegrees(Math.atan2(dx, -dy)); // 0 = up, clockwise positive
        if (angle < 0) angle += 360;
        int count = options.size();
        return (int) Math.round(angle / (360.0 / count)) % count;
    }

    private int[] positionFor(int index) {
        int count = options.size();
        double angle = Math.toRadians(index * (360.0 / count));
        int cx = width / 2 + (int) Math.round(RADIUS * Math.sin(angle));
        int cy = height / 2 - (int) Math.round(RADIUS * Math.cos(angle));
        return new int[] {cx - OPTION_W / 2, cy - OPTION_H / 2};
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xC0101010);

        Integer hovered = hoveredIndex(mouseX, mouseY);
        for (int i = 0; i < options.size(); i++) {
            int[] pos = positionFor(i);
            boolean isHovered = hovered != null && hovered == i;
            int color = isHovered ? 0xFF3A6EA5 : 0xFF303030;
            g.fill(pos[0], pos[1], pos[0] + OPTION_W, pos[1] + OPTION_H, color);
            String label = options.get(i).label();
            g.text(font, label, pos[0] + (OPTION_W - font.width(label)) / 2, pos[1] + (OPTION_H - 9) / 2, 0xFFFFFFFF);
        }

        g.fill(width / 2 - DEAD_ZONE / 2, height / 2 - DEAD_ZONE / 2, width / 2 + DEAD_ZONE / 2, height / 2 + DEAD_ZONE / 2, 0xFF505050);

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
