package com.ardor.struct;

import com.ardor.client.StatusIndicator;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Records real block placements while the player builds normally -- "position/direction/whether
 * they're shifting when they place blocks" from the original ask, captured from an actual human
 * session rather than synthesized (see BuildPlanner for the alternative when no such session
 * exists). Reuses AutoSourceRecorder's own pattern for detecting real interactions:
 * UseBlockCallback fires client-side before vanilla resolves the click, so a candidate is staged
 * here and confirmed on the NEXT client tick by checking whether the world actually changed the
 * way a successful placement would -- this callback alone can't tell success from a blocked/failed
 * attempt (wrong item, no room, cancelled), only that a right-click-on-block was attempted.
 *
 * Only active during an explicit record session (//ardor struct record start/stop, StructCommands)
 * -- this does NOT run in the background against every player action; it has to be opted into per
 * recording, same as MacroRecorder.
 */
public final class PlacementRecorder {

    private static boolean recording;
    private static StructFile current;
    private static BlockPos origin;
    private static boolean registered;

    private static boolean pending;
    private static BlockPos pendingPlacePos;
    private static Direction pendingFace;
    private static double pendingStandX, pendingStandY, pendingStandZ;
    private static float pendingYaw, pendingPitch;
    private static boolean pendingShift;
    private static int ticksSincePrevious;

    private PlacementRecorder() {}

    public static boolean isRecording() {
        return recording;
    }

    public static void start(String name) {
        current = new StructFile();
        current.name = name;
        current.timeCreated = System.currentTimeMillis();
        LocalPlayer player = Minecraft.getInstance().player;
        origin = player != null ? player.blockPosition() : BlockPos.ZERO;
        ticksSincePrevious = 0;
        pending = false;
        recording = true;
        ensureRegistered();
        StatusIndicator.show("Recording struct '" + name + "' -- build normally, //ardor struct record stop when done");
    }

    public static void stop() {
        if (!recording) return;
        recording = false;
        current.blocks = derivedSnapshot();
        StructStore.save(current);
        StatusIndicator.show("Saved struct '" + current.name + "' (" + current.placements.size() + " placement(s))");
        current = null;
    }

    private static java.util.List<StructFile.BlockEntry> derivedSnapshot() {
        // The recorded placement log already carries every block+properties+position placed --
        // the "final state" snapshot used for preview is just that, deduplicated by position so a
        // block replaced mid-session (rare, but possible) keeps only its last placement.
        java.util.Map<BlockPos, StructFile.PlacementEntry> byPos = new java.util.LinkedHashMap<>();
        for (StructFile.PlacementEntry p : current.placements) {
            byPos.put(new BlockPos(p.x, p.y, p.z), p);
        }
        java.util.List<StructFile.BlockEntry> snapshot = new java.util.ArrayList<>();
        for (StructFile.PlacementEntry p : byPos.values()) {
            StructFile.BlockEntry b = new StructFile.BlockEntry();
            b.x = p.x;
            b.y = p.y;
            b.z = p.z;
            b.block = p.block;
            b.properties = p.properties;
            snapshot.add(b);
        }
        return snapshot;
    }

    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            // Client-side firing only ever reflects the local player's own input -- other players'
            // interactions arrive through world-state sync, not this callback -- but the event's
            // declared type is the general Player, so confirm before the LocalPlayer-only fields below.
            if (recording && level.isClientSide() && hand == InteractionHand.MAIN_HAND && player instanceof LocalPlayer local) {
                stagePending(local, hitResult);
            }
            return InteractionResult.PASS;
        });
    }

    private static void stagePending(LocalPlayer player, BlockHitResult hitResult) {
        var stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof BlockItem)) return;

        BlockPos against = hitResult.getBlockPos();
        Direction face = hitResult.getDirection();
        boolean replaceInPlace = player.level().getBlockState(against).canBeReplaced();
        pendingPlacePos = replaceInPlace ? against : against.relative(face);
        pendingFace = replaceInPlace ? Direction.UP : face; // arbitrary but consistent -- see class doc on approximation
        pendingStandX = player.getX();
        pendingStandY = player.getY();
        pendingStandZ = player.getZ();
        pendingYaw = player.getYRot();
        pendingPitch = player.getXRot();
        pendingShift = player.input.keyPresses.shift();
        pending = true;
    }

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick(client);
            } catch (RuntimeException e) {
                System.err.println("[ardor] struct recorder tick failed, stopping: " + e);
                recording = false;
            }
        });
    }

    private static void tick(Minecraft client) {
        if (!recording) return;
        ticksSincePrevious++;
        if (!pending) return;
        pending = false;

        BlockState placed = client.level.getBlockState(pendingPlacePos);
        if (placed.isAir()) return; // attempt failed (blocked, wrong item resolved to nothing, etc.) -- nothing to log

        StructFile.PlacementEntry entry = new StructFile.PlacementEntry();
        entry.x = pendingPlacePos.getX() - origin.getX();
        entry.y = pendingPlacePos.getY() - origin.getY();
        entry.z = pendingPlacePos.getZ() - origin.getZ();
        entry.block = BlockStateCodec.id(placed);
        entry.properties = BlockStateCodec.properties(placed);
        entry.standX = pendingStandX - origin.getX();
        entry.standY = pendingStandY - origin.getY();
        entry.standZ = pendingStandZ - origin.getZ();
        entry.face = pendingFace.name();
        entry.yaw = pendingYaw;
        entry.pitch = pendingPitch;
        entry.shift = pendingShift;
        entry.tickDelta = ticksSincePrevious;
        current.placements.add(entry);
        ticksSincePrevious = 0;
    }
}
