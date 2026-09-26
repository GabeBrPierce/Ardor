package com.ardor.client;

import com.ardor.bridge.BaritoneNav;
import com.ardor.container.CachedItem;
import com.ardor.container.ContainerCache;
import com.ardor.container.ContainerSource;
import com.ardor.container.SourceManager;
import com.ardor.container.SourceType;
import com.ardor.game.GameActionController;
import com.ardor.game.HarvestController;
import com.ardor.game.KillAllController;
import com.ardor.game.KillPercentController;
import com.ardor.game.PathfindingController;
import com.ardor.game.SelectorResolver;
import com.ardor.region.RegionManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.client.player.ClientHotbarScrollEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * "Single Selection" from ArdorWheelScreen -- a persistent toggle (same start()/stop()/isActive()
 * shape as GrindModeController), not a held key. While active, every tick reads the same cached
 * hitResult PingKey used to: an entity under the crosshair highlights that entity; a container
 * block (level.getBlockEntity(pos) instanceof Container) highlights that block; otherwise a
 * hologram point is projected along the look ray at a distance the player adjusts with the scroll
 * wheel (ClientHotbarScrollEvents.ALLOW, returning false only while the hologram is what's showing
 * so normal hotbar scrolling is untouched the rest of the time -- see ensureScrollHook).
 *
 * Ground alignment + Go Here: the actual point-computation (scroll distance -> raw ray point ->
 * ground-align beyond touch range/when embedded in solid, or Alt to bypass) now lives in
 * GroundAlignedTargeting, extracted so AreaSelectionMode's Corners mode can pick each corner "the
 * same logic as Single Selection" (per the user's request) instead of just the raw crosshair
 * block -- see that class for the full mechanics. tryGoHere() is what PickWheelKey calls on a
 * plain tap while a ground-aligned target exists; a tap while looking at an entity/container (or
 * with Alt held, or within touch range) instead falls through to normal vanilla pick-block, same
 * as before this existed.
 *
 * overlayLines()/overlayIcon() feed SingleSelectionOverlay's WAILA-style HUD text -- see there for
 * rendering.
 *
 * Entity sub-wheel: entitySubWheelOptions() feeds PickWheelKey a follow/kill/kill all/defend
 * ArdorWheelScreen (a second wheel, opened by holding middle-click again, exactly the same
 * gesture as the main wheel -- see TODO.md's Ardor Wheel Phase 2 entry for that design) whenever
 * the current target is an entity.
 *
 * Container edit-hook: tryEditContainer() is tryGoHere()'s container-kind counterpart -- a
 * container has exactly one contextual action (edit its ContainerSource), so it's a plain tap,
 * not a sub-wheel, same "single option stays a tap" rule Go Here already follows.
 * SourceManager.findOrCreatePhysicalAt registers a PHYSICAL source there first if none exists yet
 * (mirroring AutoSourceRecorder's right-click auto-registration, just triggered by the wheel's
 * tap instead). No floating-in-3D-space panel above the container -- confirmed via jar inspection
 * that this MC version's projection matrix lives in a GPU buffer, not a plain CPU-side Matrix4f,
 * which would make real world-anchored billboard rendering meaningfully riskier to get right
 * without a live test than the rest of this feature; the container's name/contents/expected-items
 * info goes in SingleSelectionOverlay's existing top-of-screen box instead (now multi-line),
 * reusing that already-proven rendering path rather than guessing at new GPU-buffer readback code.
 */
public final class SingleSelectionMode {

    private enum Kind { NONE, ENTITY, CONTAINER, HOLOGRAM }

    private static final double DEFAULT_DISTANCE = 5.0;

    private static volatile boolean active;
    private static boolean tickerRegistered;
    private static boolean scrollHookRegistered;

    private static double distance = DEFAULT_DISTANCE;
    private static Kind kind = Kind.NONE;
    private static String context;
    private static AABB highlightBox;
    private static Vec3 hologramPoint;
    private static boolean groundAligned;
    private static BlockPos goHereTarget;
    private static List<String> overlayLines = List.of();
    private static ItemStack overlayIcon = ItemStack.EMPTY;
    private static Entity targetEntity;
    private static BlockPos targetContainerPos;

    private SingleSelectionMode() {}

    public static void toggle() {
        if (active) stop(); else start();
    }

    public static void start() {
        AreaSelectionMode.stop(); // mutually exclusive -- both hook the same tap gesture
        active = true;
        distance = DEFAULT_DISTANCE;
        ensureTicker();
        ensureScrollHook();
    }

    public static void stop() {
        active = false;
        clearState();
    }

    public static boolean isActive() {
        return active;
    }

    /** "player is looking at: ..." line for VoicePipeline, or null if not active / nothing resolvable. */
    public static String currentContext() {
        return context;
    }

    /** The entity/container box to highlight this frame, or null (hologram showing instead, or inactive). Used by RegionRenderer. */
    public static AABB currentHighlightBox() {
        return highlightBox;
    }

    /** The hologram world-space point to draw this frame, or null (a highlight box showing instead, or inactive). Used by RegionRenderer. */
    public static Vec3 currentHologramPoint() {
        return hologramPoint;
    }

    /** The WAILA-style lines for SingleSelectionOverlay (1 for entity/hologram, up to 3 for a container), or an empty list if inactive / nothing to show. */
    public static List<String> overlayLines() {
        return active ? overlayLines : List.of();
    }

    /** The icon to draw next to overlayLine(), or ItemStack.EMPTY (no icon) -- never null. */
    public static ItemStack overlayIcon() {
        return active ? overlayIcon : ItemStack.EMPTY;
    }

    /**
     * Called on a plain tap while active. If the hologram is currently ground-aligned, walks the
     * bot there (BaritoneNav.goTo, same call GoHereMixin uses for Xaero's map) and returns true;
     * otherwise returns false so PickWheelKey falls through to normal vanilla pick-block.
     */
    public static boolean tryGoHere() {
        if (!active || goHereTarget == null) return false;
        BlockPos target = goHereTarget; // captured before stop() clears it below
        if (com.ardor.game.FlightNav.available()) {
            com.ardor.game.FlightNav.flyTo(net.minecraft.world.phys.Vec3.atCenterOf(target), null, reason ->
                    StatusIndicator.show("Go Here failed: " + reason));
        } else {
            BaritoneNav.goTo(target.getX(), target.getY(), target.getZ());
        }
        StatusIndicator.show("Go Here @" + target.getX() + "," + target.getY() + "," + target.getZ());
        stop(); // "select and go" is a completed action -- don't leave the hologram/overlay hanging around after it
        return true;
    }

    /**
     * Called on a plain tap while active. If the current target is a container, opens
     * SourceEditScreen for it (registering a PHYSICAL source there first if none exists yet) and
     * returns true; otherwise returns false so PickWheelKey falls through to tryGoHere() / normal
     * vanilla pick-block.
     */
    public static boolean tryEditContainer() {
        if (!active || kind != Kind.CONTAINER || targetContainerPos == null) return false;
        ContainerSource source = SourceManager.get().findOrCreatePhysicalAt(targetContainerPos, SourceType.PHYSICAL);
        Minecraft.getInstance().gui.setScreen(new SourceEditScreen(source.id));
        stop(); // same "selection is a completed action" reasoning as tryGoHere()
        return true;
    }

    /**
     * The follow/kill/kill all/defend wedges for PickWheelKey's hold-to-open, or null if the
     * current target isn't an entity (so PickWheelKey opens the main wheel instead). Follow
     * (PathfindingController.followEntity) and single-target Kill (GameActionController.
     * attackEntityUntilDead) reuse existing entry points; Kill All (KillAllController) is a new,
     * small addition -- see TODO.md. Defend classifies the targeted entity into one of the three
     * Region Behavior Settings categories (SelectorResolver.categoryOf) and bumps THAT category to
     * Reactive on the region the player is currently standing in (RegionManager.
     * bumpAggressivenessForCurrentRegion) -- e.g. pointing Defend at a zombie opts this region into
     * fighting back against hostiles, without touching anything broader.
     */
    public static List<ArdorWheelScreen.WheelOption> entitySubWheelOptions() {
        if (!active || kind != Kind.ENTITY || targetEntity == null) return null;
        Entity entity = targetEntity;
        String name = entity.getName().getString();
        return List.of(
                new ArdorWheelScreen.WheelOption("Follow", () -> {
                    PathfindingController.followEntity(entity);
                    StatusIndicator.show("Following " + name);
                }),
                new ArdorWheelScreen.WheelOption("Kill", () -> {
                    GameActionController.attackEntityUntilDead(entity);
                    StatusIndicator.show("Attacking " + name);
                }),
                new ArdorWheelScreen.WheelOption("Kill All", () -> {
                    KillAllController.start(entity.getType());
                    StatusIndicator.show("Killing all " + name + "-type entities nearby");
                }),
                new ArdorWheelScreen.WheelOption("Kill % " + name, () ->
                        promptPercent("What percent of " + name + " should we kill?", pct ->
                                KillPercentController.startByType(entity.getType(), name, pct))),
                new ArdorWheelScreen.WheelOption("Kill % Hostile", () ->
                        promptPercent("What percent of Hostile mobs should we kill?", pct ->
                                KillPercentController.startByCategory("hostile", pct))),
                new ArdorWheelScreen.WheelOption("Kill % Passive", () ->
                        promptPercent("What percent of Passive mobs should we kill?", pct ->
                                KillPercentController.startByCategory("passive", pct))),
                new ArdorWheelScreen.WheelOption("Defend", () -> {
                    String category = SelectorResolver.categoryOf(entity);
                    if (category == null) {
                        StatusIndicator.show(name + " doesn't fit any Region Behavior Setting category.");
                    } else if (RegionManager.get().bumpAggressivenessForCurrentRegion(category)) {
                        StatusIndicator.show("This region now fights back against " + category + " entities.");
                    } else {
                        StatusIndicator.show("This region is already Reactive (or higher) against " + category + " entities.");
                    }
                })
        );
    }

    /**
     * The block sub-wheel -- go/break/harvest actions for whatever the ground-aligned aim currently
     * resolves to (see GroundAlignedTargeting -- goHereTarget is the STANDING position; the actual
     * block is goHereTarget.below(), same as the existing "Go Here: <block name>" overlay text
     * already computes). Only ever non-null for the HOLOGRAM kind (never entity/container, which
     * have their own dedicated wheels) so this doesn't change anything about how those already work.
     */
    public static List<ArdorWheelScreen.WheelOption> blockSubWheelOptions() {
        if (!active || kind != Kind.HOLOGRAM || !groundAligned || goHereTarget == null) return null;
        BlockPos standPos = goHereTarget;
        BlockPos belowPos = standPos.below();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;

        // Crops (wheat family, nether wart, sugar cane) are non-solid and occupy the STAND position
        // itself, not the block below it -- unlike a solid block (melon/pumpkin included), which is
        // exactly what "below" already means. Check both; prefer whichever actually classifies.
        BlockState standState = mc.level.getBlockState(standPos);
        HarvestController.CropKind standCrop = HarvestController.classify(mc.level, standPos, standState);
        BlockPos blockPos = standCrop != null ? standPos : belowPos;
        BlockState state = standCrop != null ? standState : mc.level.getBlockState(belowPos);
        String blockName = state.getBlock().getName().getString();

        List<ArdorWheelScreen.WheelOption> options = new ArrayList<>();
        options.add(new ArdorWheelScreen.WheelOption("Go to", () -> goToStandPos(standPos)));
        options.add(new ArdorWheelScreen.WheelOption("Select Area", AreaSelectionMode::startCorners));
        options.add(new ArdorWheelScreen.WheelOption("Break Block", () -> BlockWheelActions.breakOne(blockPos)));
        options.add(new ArdorWheelScreen.WheelOption("Break # of Blocks", () ->
                promptCount("How many " + blockName + " should we break?", n ->
                        BlockWheelActions.breakCount(state.getBlock(), blockPos, n))));

        HarvestController.CropKind crop = standCrop != null ? standCrop : HarvestController.classify(mc.level, blockPos, state);
        if (crop != null) {
            options.add(new ArdorWheelScreen.WheelOption("Harvest One", () -> HarvestController.harvestOne(blockPos, crop)));
            options.add(new ArdorWheelScreen.WheelOption("Harvest %", () ->
                    promptPercent("What percent of crops should we harvest?", pct ->
                            HarvestController.harvestPercent(blockPos, crop, pct))));
            options.add(new ArdorWheelScreen.WheelOption("Tend Field", () -> HarvestController.startTendField(blockPos, crop)));
        }
        return options;
    }

    /** Whichever sub-wheel applies to the current target (entity, then block), or a bare "Go Here" fallback if neither does -- used by Sims mode's right-click (PickWheelKey's hold gesture already checks entitySubWheelOptions()/the main wheel separately and doesn't need this). */
    public static List<ArdorWheelScreen.WheelOption> currentSubWheelOptions() {
        List<ArdorWheelScreen.WheelOption> entityOptions = entitySubWheelOptions();
        if (entityOptions != null) return entityOptions;
        List<ArdorWheelScreen.WheelOption> blockOptions = blockSubWheelOptions();
        if (blockOptions != null) return blockOptions;
        if (kind == Kind.CONTAINER) {
            return List.of(new ArdorWheelScreen.WheelOption("Edit Container", () -> tryEditContainer()));
        }
        return List.of(new ArdorWheelScreen.WheelOption("Go Here", SingleSelectionMode::tryGoHere));
    }

    private static void goToStandPos(BlockPos standPos) {
        if (com.ardor.game.FlightNav.available()) {
            com.ardor.game.FlightNav.flyTo(net.minecraft.world.phys.Vec3.atCenterOf(standPos), null, reason ->
                    StatusIndicator.show("Go to failed: " + reason));
        } else {
            BaritoneNav.goTo(standPos.getX(), standPos.getY(), standPos.getZ());
        }
        StatusIndicator.show("Going to @" + standPos.getX() + "," + standPos.getY() + "," + standPos.getZ());
    }

    private static void promptPercent(String question, java.util.function.IntConsumer onPercent) {
        Minecraft.getInstance().gui.setScreen(new TextInputPromptScreen(question, answer -> {
            if (answer == null) return;
            try {
                int pct = Math.max(0, Math.min(100, Integer.parseInt(answer.trim().replace("%", ""))));
                onPercent.accept(pct);
            } catch (NumberFormatException e) {
                StatusIndicator.show("Not a number: " + answer);
            }
        }));
    }

    private static void promptCount(String question, java.util.function.IntConsumer onCount) {
        Minecraft.getInstance().gui.setScreen(new TextInputPromptScreen(question, answer -> {
            if (answer == null) return;
            try {
                int n = Math.max(1, Integer.parseInt(answer.trim()));
                onCount.accept(n);
            } catch (NumberFormatException e) {
                StatusIndicator.show("Not a number: " + answer);
            }
        }));
    }

    private static void clearState() {
        kind = Kind.NONE;
        context = null;
        highlightBox = null;
        hologramPoint = null;
        groundAligned = false;
        goHereTarget = null;
        overlayLines = List.of();
        overlayIcon = ItemStack.EMPTY;
        targetEntity = null;
        targetContainerPos = null;
    }

    private static void ensureTicker() {
        if (tickerRegistered) return;
        tickerRegistered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick(client);
            } catch (RuntimeException e) {
                System.err.println("[ardor] single selection mode failed: " + e);
            }
        });
    }

    private static void ensureScrollHook() {
        if (scrollHookRegistered) return;
        scrollHookRegistered = true;
        ClientHotbarScrollEvents.ALLOW.register((inventory, oldSlot, newSlot, scrollX, scrollY) -> {
            // Sims mode's own scroll hook (SimsCameraController) owns scroll for dolly/zoom instead
            // -- the ground-aligned "aim distance" concept below is meaningless for a cursor-based
            // target (Sims already knows exactly what's under the cursor via a real raycast, it
            // never needs to guess a distance along a fixed ray the way the crosshair-based hologram
            // does), and letting both react to the same scroll at once would fight each other.
            if (CameraModeController.mode() == CameraModeController.Mode.SIMS) return true;
            if (!active || kind != Kind.HOLOGRAM) return true;
            distance = Math.max(GroundAlignedTargeting.MIN_DISTANCE, Math.min(GroundAlignedTargeting.MAX_DISTANCE, distance + scrollY));
            return false;
        });
    }

    private static void tick(Minecraft client) {
        if (!active) return;
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            clearState();
            return;
        }

        HitResult hit = CameraModeController.mode() == CameraModeController.Mode.SIMS
                ? SimsCameraController.cursorHit()
                : client.hitResult;

        if (hit != null && hit.getType() == HitResult.Type.ENTITY && hit instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            kind = Kind.ENTITY;
            targetEntity = entity;
            targetContainerPos = null;
            highlightBox = entity.getBoundingBox();
            hologramPoint = null;
            groundAligned = false;
            goHereTarget = null;
            overlayLines = List.of(entity.getName().getString());
            overlayIcon = ItemStack.EMPTY;
            Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            context = "player is looking at: " + id + " @" + (int) entity.getX() + "," + (int) entity.getY() + "," + (int) entity.getZ();
            return;
        }

        if (hit != null && hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit) {
            BlockPos pos = blockHit.getBlockPos();
            if (client.level.getBlockEntity(pos) instanceof Container) {
                BlockState state = client.level.getBlockState(pos);
                kind = Kind.CONTAINER;
                targetEntity = null;
                targetContainerPos = pos;
                highlightBox = new AABB(pos);
                hologramPoint = null;
                groundAligned = false;
                goHereTarget = null;
                overlayLines = buildContainerOverlayLines(state, pos);
                overlayIcon = new ItemStack(state.getBlock().asItem());
                Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                context = "player is looking at: " + id + " (container) @" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
                return;
            }
        }

        kind = Kind.HOLOGRAM;
        targetEntity = null;
        targetContainerPos = null;
        highlightBox = null;

        GroundAlignedTargeting.Result targeting = GroundAlignedTargeting.compute(client, player, distance);
        if (targeting.groundAligned()) {
            BlockPos ground = targeting.groundPos();
            hologramPoint = targeting.point();
            groundAligned = true;
            goHereTarget = ground;
            BlockState below = client.level.getBlockState(ground.below());
            overlayLines = List.of("Go Here: " + below.getBlock().getName().getString());
            overlayIcon = new ItemStack(below.getBlock().asItem());
            context = "player is aiming at: @" + ground.getX() + "," + ground.getY() + "," + ground.getZ() + " (Go Here available)";
        } else {
            hologramPoint = targeting.point();
            groundAligned = false;
            goHereTarget = null;
            overlayLines = List.of("Aim Point (" + Math.round(distance) + "m)");
            overlayIcon = ItemStack.EMPTY;
            context = "player is aiming at: @" + Math.round(hologramPoint.x) + "," + Math.round(hologramPoint.y) + "," + Math.round(hologramPoint.z);
        }
    }

    /** Container line 1 is always the block name; lines 2+ only appear once a ContainerSource is registered there (read-only lookup -- registration itself only happens on tap, via tryEditContainer()). */
    private static List<String> buildContainerOverlayLines(BlockState state, BlockPos pos) {
        List<String> lines = new ArrayList<>();
        lines.add(state.getBlock().getName().getString() + " (container)");

        ContainerSource source = SourceManager.get().findByPosition(pos);
        if (source == null) {
            lines.add("Not registered -- tap to add");
            return lines;
        }

        List<CachedItem> items = ContainerCache.itemsFor(source.id);
        if (items.isEmpty()) {
            lines.add("Contents: (empty, or not yet scanned)");
        } else {
            String summary = items.stream().limit(3)
                    .map(i -> i.displayName + " x" + i.count)
                    .collect(Collectors.joining(", "));
            if (items.size() > 3) summary += ", +" + (items.size() - 3) + " more";
            lines.add("Contents: " + summary);
        }

        lines.add(source.desiredContents.isEmpty()
                ? "Tap to edit"
                : "Expected: " + source.desiredContents.size() + " filter(s) -- tap to edit");
        return lines;
    }

}
