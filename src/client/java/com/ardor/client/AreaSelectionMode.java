package com.ardor.client;

import com.ardor.game.BreakAreaController;
import com.ardor.game.KillAllController;
import com.ardor.region.RegionManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.client.player.ClientHotbarScrollEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * "Area Selection" from ArdorWheelScreen's main wheel -- picking it opens a Radius/Corners choice
 * wheel (startRadius()/startCorners()); either mode ends by opening the "Set As Region" / "Break
 * Blocks Within" / "Kill Hostile Mobs" follow-up wheel over the captured area. Mutually exclusive
 * with SingleSelectionMode (starting one stops the other) since both hook the same tap gesture.
 *
 * Radius mode: a SQUARE centered on the PLAYER (not a circle), sized by scroll (same
 * ClientHotbarScrollEvents.ALLOW hook SingleSelectionMode's hologram distance uses), with an
 * aspect ratio that shifts by look pitch -- looking straight up/down (|pitch| near 90) expands the
 * vertical extent, looking level (|pitch| near 0) expands the horizontal extent, blended linearly
 * between the two. This is a literal reading of a loose verbal spec ("looking up/down expand
 * vertically... looking more toward the sides expand horizontally") -- adjust computeRadiusBox's
 * math if the feel is wrong once tested live.
 *
 * Corners mode: two sequential taps, each picking a point via GroundAlignedTargeting -- the exact
 * same scroll-adjustable, ground-aligned targeting logic Single Selection's hologram uses (per
 * the user's explicit request that "the corner selection should use the same logic as the single
 * selection"), rather than just the raw block directly under the crosshair. Each corner gets its
 * own independent distance (reset to the default when moving on to the second corner) via the
 * same scroll hook Radius mode uses for its own sizing.
 */
public final class AreaSelectionMode {

    private enum Mode { RADIUS, CORNERS }
    private enum CornerPhase { PICK_FIRST, PICK_SECOND }

    private static final double DEFAULT_RADIUS = 5.0;
    private static final double MIN_RADIUS = 2.0;
    private static final double MAX_RADIUS = 48.0;
    private static final double MIN_HALF_EXTENT = 2.0; // never collapse to a pancake/needle

    private static volatile boolean active;
    private static boolean tickerRegistered;
    private static boolean scrollHookRegistered;

    private static Mode mode;
    private static double radius = DEFAULT_RADIUS;
    private static double cornerDistance;
    private static CornerPhase cornerPhase;
    private static BlockPos corner1;

    private static AABB previewBox;

    private AreaSelectionMode() {}

    public static boolean isActive() {
        return active;
    }

    /** The box to render this frame (region-color style, see RegionRenderer), or null if inactive / nothing to show yet. */
    public static AABB currentPreviewBox() {
        return previewBox;
    }

    public static void startRadius() {
        SingleSelectionMode.stop();
        mode = Mode.RADIUS;
        radius = DEFAULT_RADIUS;
        active = true;
        ensureTicker();
        ensureScrollHook();
    }

    public static void startCorners() {
        SingleSelectionMode.stop();
        mode = Mode.CORNERS;
        cornerPhase = CornerPhase.PICK_FIRST;
        corner1 = null;
        cornerDistance = DEFAULT_RADIUS;
        active = true;
        ensureTicker();
        ensureScrollHook();
        StatusIndicator.show("Aim and scroll to place the first corner, tap to set it");
    }

    public static void stop() {
        active = false;
        previewBox = null;
        corner1 = null;
    }

    /**
     * Called on a plain tap while active. Radius mode: confirms the current box immediately.
     * Corners mode: picks the currently-aimed block as the next corner (or just shows a status
     * line and stays on the same step if nothing solid is in range). Either way, once the area is
     * fully captured, opens the follow-up wheel and stops. Returns false only when inactive, so
     * PickWheelKey always treats a tap as "handled" while area selection is in progress -- there's
     * no vanilla pick-block fallback mid-selection.
     */
    public static boolean tryConfirm() {
        if (!active) return false;

        if (mode == Mode.RADIUS) {
            AABB box = previewBox;
            stop();
            if (box != null) openFollowUpWheel(box);
            return true;
        }

        BlockPos picked = currentCornerTarget();
        if (picked == null) return true; // no player/level loaded -- nothing to pick yet

        if (cornerPhase == CornerPhase.PICK_FIRST) {
            corner1 = picked;
            cornerPhase = CornerPhase.PICK_SECOND;
            cornerDistance = DEFAULT_RADIUS;
            StatusIndicator.show("First corner set -- aim at the second and tap again");
            return true;
        }

        AABB box = new AABB(corner1.getX(), corner1.getY(), corner1.getZ(), picked.getX() + 1, picked.getY() + 1, picked.getZ() + 1);
        stop();
        openFollowUpWheel(box);
        return true;
    }

    /** The current corner candidate, via GroundAlignedTargeting (same logic Single Selection's hologram uses) -- ground-aligned position if aligned, otherwise the raw point floored to a block position. Null only if there's no player/level loaded. */
    private static BlockPos currentCornerTarget() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.level == null) return null;
        GroundAlignedTargeting.Result result = GroundAlignedTargeting.compute(client, player, cornerDistance);
        return result.groundAligned() ? result.groundPos() : BlockPos.containing(result.point().x, result.point().y, result.point().z);
    }

    private static void openFollowUpWheel(AABB box) {
        Minecraft.getInstance().setScreen(new ArdorWheelScreen(List.of(
                new ArdorWheelScreen.WheelOption("Set As Region", () -> setAsRegion(box)),
                new ArdorWheelScreen.WheelOption("Break Blocks Within", () -> startBreakBlocksWithin(box)),
                new ArdorWheelScreen.WheelOption("Kill Hostile Mobs", () -> {
                    KillAllController.startHostilesInArea(box);
                    StatusIndicator.show("Killing hostiles in the selected area");
                })
        )));
    }

    /**
     * No rename UI exists anywhere in this codebase (only RegionListScreen's create-with-typed-
     * name and RegionEditScreen's bounds/parent/flags editor) -- auto-generates "area_N" and opens
     * RegionEditScreen straight away so bounds/parent/flags are still editable, same flow
     * RegionListScreen.onNew() already uses for its own player-centered default region. See
     * TODO.md for the naming gap.
     */
    private static void setAsRegion(AABB box) {
        String profileKey = RegionManager.currentProfileKey();
        String name = RegionManager.get().nextAutoRegionName(profileKey);
        BlockPos a = BlockPos.containing(box.minX, box.minY, box.minZ);
        BlockPos b = BlockPos.containing(box.maxX - 1, box.maxY - 1, box.maxZ - 1);
        RegionManager.get().setRegion(profileKey, name, a, b);
        Minecraft.getInstance().setScreen(new RegionEditScreen(name));
    }

    /**
     * Capacity is checked up front (BreakAreaController.hasRoughCapacityFor) before anything
     * starts breaking -- short on room opens a 2-option wheel: "Add More Containers" actually
     * obtains one (BreakAreaController.obtainAndPlaceContainer -- look for an existing chest,
     * else craft one from planks/logs via PathfindingController.ensureChests, then find a clear
     * spot near the site and place it) and, once placed, starts the run with it as the dump
     * target so the bot empties into it mid-run instead of just filling up again. "Cancel" backs
     * out instead.
     */
    private static void startBreakBlocksWithin(AABB box) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        List<BlockPos> blocks = BreakAreaController.enumerate(box, level);
        if (blocks.isEmpty()) {
            StatusIndicator.show("Nothing breakable in that area.");
            return;
        }
        if (!BreakAreaController.hasRoughCapacityFor(blocks.size())) {
            Minecraft.getInstance().setScreen(new ArdorWheelScreen(List.of(
                    new ArdorWheelScreen.WheelOption("Add More Containers", () -> {
                        StatusIndicator.show("Obtaining a container...");
                        BreakAreaController.obtainAndPlaceContainer(box, placedAt -> {
                            if (placedAt != null) {
                                StatusIndicator.show("Container placed -- starting Break Blocks Within.");
                            } else {
                                StatusIndicator.show("Couldn't get a container -- starting anyway, inventory may fill up.");
                            }
                            BreakAreaController.start(blocks, placedAt);
                        });
                    }),
                    new ArdorWheelScreen.WheelOption("Cancel", () ->
                            StatusIndicator.show("Break Blocks Within cancelled."))
            )));
            return;
        }
        BreakAreaController.start(blocks);
    }

    private static void ensureTicker() {
        if (tickerRegistered) return;
        tickerRegistered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick(client);
            } catch (RuntimeException e) {
                System.err.println("[ardor] area selection mode failed: " + e);
            }
        });
    }

    private static void ensureScrollHook() {
        if (scrollHookRegistered) return;
        scrollHookRegistered = true;
        ClientHotbarScrollEvents.ALLOW.register((inventory, oldSlot, newSlot, scrollX, scrollY) -> {
            if (!active) return true;
            if (mode == Mode.RADIUS) {
                radius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius + scrollY));
            } else {
                cornerDistance = Math.max(GroundAlignedTargeting.MIN_DISTANCE, Math.min(GroundAlignedTargeting.MAX_DISTANCE, cornerDistance + scrollY));
            }
            return false;
        });
    }

    private static void tick(Minecraft client) {
        if (!active) return;
        LocalPlayer player = client.player;
        if (player == null) {
            stop();
            return;
        }

        if (mode == Mode.RADIUS) {
            previewBox = computeRadiusBox(player, radius);
            return;
        }

        // CORNERS: show the current targeting point, or a growing box from corner1 to it once picked.
        BlockPos current = currentCornerTarget();
        if (current == null) {
            previewBox = null;
            return;
        }
        previewBox = cornerPhase == CornerPhase.PICK_FIRST || corner1 == null
                ? new AABB(current)
                : new AABB(corner1.getX(), corner1.getY(), corner1.getZ(), current.getX() + 1, current.getY() + 1, current.getZ() + 1);
    }

    private static AABB computeRadiusBox(LocalPlayer player, double radius) {
        float pitch = player.getXRot(); // -90 (straight up) .. 90 (straight down), 0 = level
        double verticalFactor = Math.abs(pitch) / 90.0;
        double horizontalFactor = 1.0 - verticalFactor;
        double halfY = Math.max(MIN_HALF_EXTENT, radius * verticalFactor);
        double halfXZ = Math.max(MIN_HALF_EXTENT, radius * horizontalFactor);
        Vec3 center = player.position();
        return new AABB(center.x - halfXZ, center.y - halfY, center.z - halfXZ,
                center.x + halfXZ, center.y + halfY, center.z + halfXZ);
    }
}
