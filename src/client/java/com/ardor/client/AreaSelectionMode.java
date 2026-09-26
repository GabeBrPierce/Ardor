package com.ardor.client;

import com.ardor.game.BreakAreaController;
import com.ardor.game.BuildAreaController;
import com.ardor.game.KillAllController;
import com.ardor.region.RegionManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.client.player.ClientHotbarScrollEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * "Area Selection" from ArdorWheelScreen's main wheel -- picking it opens a Radius/Corners choice
 * wheel (startRadius()/startCorners()); either mode ends by opening the "Set As Region" / "Break
 * Blocks Within" / "Kill Hostile Mobs" follow-up wheel over the captured area. Mutually exclusive
 * with SingleSelectionMode (starting one stops the other) since both hook the same tap gesture.
 *
 * Radius mode: a SQUARE centered on the PLAYER (not a circle), with independent horizontal
 * (halfXZ) and vertical (halfY) half-extents -- scrolling adjusts ONE of them, chosen by whatever
 * the player's pitch is AT THE MOMENT OF SCROLLING (VERTICAL_PITCH_THRESHOLD): looking up/down
 * steeply and scrolling grows/shrinks the vertical extent only; looking more level and scrolling
 * grows/shrinks the horizontal extent only. "It should only expand up/down if I look up/down AND
 * scroll" -- just looking around (without scrolling) no longer reshapes the box at all, unlike the
 * original continuous-pitch-blend version this replaced.
 *
 * Corners mode: each corner goes through two taps instead of one. AIM_FIRST/AIM_SECOND track the
 * crosshair every tick via the player's OWN raw hitResult -- literally whatever block is under the
 * crosshair, same as vanilla's own block-outline highlight, NOT GroundAlignedTargeting's
 * scroll-distance-projected/ground-snapped point (that logic is still what Single Selection's own
 * hologram uses, just no longer shared with Corners -- "AIM should target the block we're looking
 * at, not the block above or around it"). A tap there locks the point in place and moves to
 * NUDGE_FIRST/NUDGE_SECOND: the point stops tracking the crosshair and instead scrolling nudges it
 * one block at a time along whichever axis the player is currently facing
 * (Direction.getApproximateNearest of the look vector, resolved fresh each scroll tick so turning
 * to face a different axis changes what the next scroll does) -- this is also how you reach a block
 * beyond crosshair range or pick a different one than exactly what AIM highlighted, rather than AIM
 * itself trying to project/snap to one. A second tap sets that corner; for the first corner this
 * also kicks off AIM_SECOND for the next point, and for the second corner it opens the follow-up
 * wheel. Four taps total: aim1, lock1, aim2, lock2.
 */
public final class AreaSelectionMode {

    private enum Mode { RADIUS, CORNERS }
    private enum CornerPhase { AIM_FIRST, NUDGE_FIRST, AIM_SECOND, NUDGE_SECOND }

    private static final double DEFAULT_RADIUS = 5.0;
    private static final double MIN_RADIUS = 2.0;
    private static final double MAX_RADIUS = 48.0;
    private static final double MIN_HALF_EXTENT = 2.0; // never collapse to a pancake/needle
    private static final float VERTICAL_PITCH_THRESHOLD = 45f; // |pitch| beyond this counts as "looking up/down" for scroll routing

    private static volatile boolean active;
    private static boolean tickerRegistered;
    private static boolean scrollHookRegistered;

    private static Mode mode;
    // "It should only expand up/down if I look up/down AND scroll" -- two INDEPENDENT half-extents
    // instead of one radius blended continuously by whatever pitch happens to be at render time
    // (the old behavior: just looking around, without touching the scroll wheel at all, reshaped
    // the box every tick). Scrolling picks which one to adjust, based on pitch AT THE MOMENT OF
    // SCROLLING (ensureScrollHook) -- looking around afterward no longer changes the box at all.
    private static double halfXZ = DEFAULT_RADIUS;
    private static double halfY = MIN_HALF_EXTENT;
    private static CornerPhase cornerPhase;
    private static BlockPos corner1;
    private static BlockPos activePoint; // tracked live during AIM_*, fixed and nudged during NUDGE_*

    private static AABB previewBox;
    private static List<String> overlayLines = List.of();

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
        halfXZ = DEFAULT_RADIUS;
        halfY = MIN_HALF_EXTENT;
        active = true;
        ensureTicker();
        ensureScrollHook();
    }

    public static void startCorners() {
        SingleSelectionMode.stop();
        mode = Mode.CORNERS;
        cornerPhase = CornerPhase.AIM_FIRST;
        corner1 = null;
        activePoint = null;
        active = true;
        ensureTicker();
        ensureScrollHook();
        StatusIndicator.show("Look at the first corner, tap to lock it");
    }

    public static void stop() {
        active = false;
        previewBox = null;
        corner1 = null;
        activePoint = null;
    }

    /**
     * Called on a plain tap while active. Radius mode: confirms the current box immediately.
     * Corners mode: advances the AIM_FIRST -> NUDGE_FIRST -> AIM_SECOND -> NUDGE_SECOND state
     * machine one step (see class doc). Returns false only when inactive, so PickWheelKey always
     * treats a tap as "handled" while area selection is in progress -- there's no vanilla
     * pick-block fallback mid-selection.
     */
    public static boolean tryConfirm() {
        if (!active) return false;

        if (mode == Mode.RADIUS) {
            AABB box = previewBox;
            stop();
            if (box != null) openFollowUpWheel(box);
            return true;
        }

        if (activePoint == null) return true; // no player/level loaded -- nothing to pick yet

        switch (cornerPhase) {
            case AIM_FIRST -> {
                cornerPhase = CornerPhase.NUDGE_FIRST;
                StatusIndicator.show("First point locked -- scroll to nudge, tap to set it");
            }
            case NUDGE_FIRST -> {
                corner1 = activePoint;
                cornerPhase = CornerPhase.AIM_SECOND;
                StatusIndicator.show("First corner set -- look at the second and tap again");
            }
            case AIM_SECOND -> {
                cornerPhase = CornerPhase.NUDGE_SECOND;
                StatusIndicator.show("Second point locked -- scroll to nudge, tap to set it");
            }
            case NUDGE_SECOND -> {
                BlockPos picked = activePoint;
                AABB box = new AABB(corner1.getX(), corner1.getY(), corner1.getZ(), picked.getX() + 1, picked.getY() + 1, picked.getZ() + 1);
                stop();
                openFollowUpWheel(box);
            }
        }
        return true;
    }

    /** Literally whatever block the crosshair (or, in Sims mode, the free cursor -- see CursorRaycast) is currently hitting -- null if nothing's in range/looked at. "Target the block we're looking at, not the block above or around it." */
    private static BlockPos currentCornerTarget() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return null;
        HitResult hit = CameraModeController.mode() == CameraModeController.Mode.SIMS
                ? SimsCameraController.cursorHit()
                : client.hitResult;
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) return null;
        return blockHit.getBlockPos();
    }

    private static void openFollowUpWheel(AABB box) {
        Minecraft.getInstance().gui.setScreen(new ArdorWheelScreen(List.of(
                new ArdorWheelScreen.WheelOption("Set As Region", () -> setAsRegion(box)),
                new ArdorWheelScreen.WheelOption("Break Blocks Within", () -> startBreakBlocksWithin(box)),
                new ArdorWheelScreen.WheelOption("Build Blocks Within", () -> startBuildBlocksWithin(box)),
                new ArdorWheelScreen.WheelOption("Kill Hostile Mobs", () -> {
                    KillAllController.startHostilesInArea(box);
                    StatusIndicator.show("Killing hostiles in the selected area");
                })
        )));
    }

    /** Opens BuildBlocksScreen to pick sources for whatever's empty in box -- same "nothing to do" short-circuit startBreakBlocksWithin already has for the opposite case. */
    private static void startBuildBlocksWithin(AABB box) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        if (BuildAreaController.enumerate(box, level).isEmpty()) {
            StatusIndicator.show("Nothing to build -- the area is already full.");
            return;
        }
        Minecraft.getInstance().gui.setScreen(new BuildBlocksScreen(box));
    }

    /**
     * Auto-generates "area_N" and opens RegionEditScreen straight away so bounds/parent/flags are
     * still editable there -- same flow RegionListScreen.onNew() already uses for its own
     * player-centered default region. The auto-generated name isn't final: RegionEditScreen's own
     * Name field can rename it to something more meaningful afterward.
     */
    private static void setAsRegion(AABB box) {
        String profileKey = RegionManager.currentProfileKey();
        String name = RegionManager.get().nextAutoRegionName(profileKey);
        BlockPos a = BlockPos.containing(box.minX, box.minY, box.minZ);
        BlockPos b = BlockPos.containing(box.maxX - 1, box.maxY - 1, box.maxZ - 1);
        RegionManager.get().setRegion(profileKey, name, a, b);
        Minecraft.getInstance().gui.setScreen(new RegionEditScreen(name));
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
            Minecraft.getInstance().gui.setScreen(new ArdorWheelScreen(List.of(
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
                LocalPlayer player = Minecraft.getInstance().player;
                float pitch = player != null ? player.getXRot() : 0f;
                if (Math.abs(pitch) >= VERTICAL_PITCH_THRESHOLD) {
                    halfY = Math.max(MIN_HALF_EXTENT, Math.min(MAX_RADIUS, halfY + scrollY));
                } else {
                    halfXZ = Math.max(MIN_HALF_EXTENT, Math.min(MAX_RADIUS, halfXZ + scrollY));
                }
                return false;
            }
            // CORNERS: AIM_* tracks the crosshair directly and doesn't need scroll -- let normal
            // hotbar scrolling through. Only NUDGE_* consumes it, to move the locked point.
            if ((cornerPhase == CornerPhase.NUDGE_FIRST || cornerPhase == CornerPhase.NUDGE_SECOND) && activePoint != null) {
                LocalPlayer player = Minecraft.getInstance().player;
                if (player != null) {
                    Vec3 look = player.getLookAngle();
                    Direction facing = Direction.getApproximateNearest(look.x, look.y, look.z);
                    activePoint = activePoint.relative(facing, (int) Math.signum(scrollY));
                }
                return false;
            }
            return true;
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
            previewBox = computeRadiusBox(player);
            overlayLines = buildRadiusOverlayLines(player, previewBox);
            return;
        }

        // CORNERS: AIM_* re-tracks the crosshair every tick; NUDGE_* leaves activePoint alone
        // (the scroll hook moves it instead) so the point doesn't jump back to the crosshair.
        if (cornerPhase == CornerPhase.AIM_FIRST || cornerPhase == CornerPhase.AIM_SECOND) {
            activePoint = currentCornerTarget();
        }
        if (activePoint == null) {
            previewBox = null;
            overlayLines = List.of();
            return;
        }
        previewBox = corner1 == null
                ? new AABB(activePoint)
                : new AABB(corner1.getX(), corner1.getY(), corner1.getZ(), activePoint.getX() + 1, activePoint.getY() + 1, activePoint.getZ() + 1);
        overlayLines = buildCornersOverlayLines(previewBox);
    }

    private static AABB computeRadiusBox(LocalPlayer player) {
        Vec3 center = player.position();
        return new AABB(center.x - halfXZ, center.y - halfY, center.z - halfXZ,
                center.x + halfXZ, center.y + halfY, center.z + halfXZ);
    }

    /** "The area selector needs to show the same kind of WAILA display Single Selection does -- the size of the area and the position of the first point." Radius mode has no distinct "first point" (it's centered on the player), so the closest analogous line is the center position. */
    private static List<String> buildRadiusOverlayLines(LocalPlayer player, AABB box) {
        List<String> lines = new ArrayList<>();
        lines.add("Area Selection: Radius");
        lines.add(sizeLine(box));
        BlockPos center = player.blockPosition();
        lines.add("Center: " + center.getX() + ", " + center.getY() + ", " + center.getZ());
        return lines;
    }

    private static List<String> buildCornersOverlayLines(AABB box) {
        List<String> lines = new ArrayList<>();
        lines.add("Area Selection: Corners (" + cornerPhaseLabel() + ")");
        lines.add(sizeLine(box));
        if (corner1 != null) {
            lines.add("First corner: " + corner1.getX() + ", " + corner1.getY() + ", " + corner1.getZ());
        }
        return lines;
    }

    private static String cornerPhaseLabel() {
        return switch (cornerPhase) {
            case AIM_FIRST, AIM_SECOND -> "look at target, tap to lock";
            case NUDGE_FIRST, NUDGE_SECOND -> "scroll to nudge, tap to set";
        };
    }

    private static String sizeLine(AABB box) {
        int sizeX = (int) Math.round(box.maxX - box.minX);
        int sizeY = (int) Math.round(box.maxY - box.minY);
        int sizeZ = (int) Math.round(box.maxZ - box.minZ);
        return "Size: " + sizeX + " x " + sizeY + " x " + sizeZ;
    }

    /** WAILA-style overlay text (see SingleSelectionOverlay, which renders this alongside its own when Single Selection isn't the one active) -- empty if inactive or nothing to show yet. */
    public static List<String> overlayLines() {
        return active ? overlayLines : List.of();
    }
}
