package com.ardor.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * The scroll-adjustable, ground-aligned point-targeting SingleSelectionMode's hologram uses,
 * extracted so AreaSelectionMode's Corners mode can pick each corner "the same logic as Single
 * Selection" instead of just the raw block directly under the crosshair (per the user's own
 * explicit request) -- beyond TOUCH_RANGE or embedded in a solid block, the point snaps to the
 * nearest ground below; holding Alt bypasses that back to the raw floating point. See
 * SingleSelectionMode's own class doc for the full rationale (this is the same logic, just no
 * longer private to that class).
 */
public final class GroundAlignedTargeting {

    public static final double TOUCH_RANGE = 5.0; // beyond this, the point isn't something you could just walk up and interact with directly
    public static final double MIN_DISTANCE = 1.0;
    public static final double MAX_DISTANCE = 64.0;
    private static final int GROUND_SCAN_DEPTH = 64;

    /** point is where the marker renders; groundAligned/groundPos describe whether (and where) it snapped to ground -- groundPos is null unless groundAligned is true. */
    public record Result(Vec3 point, boolean groundAligned, BlockPos groundPos) {}

    private GroundAlignedTargeting() {}

    public static Result compute(Minecraft client, LocalPlayer player, double distance) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        Vec3 raw = eye.add(look.scale(distance));

        boolean altHeld = isAltHeld();
        boolean embedded = !client.level.getBlockState(BlockPos.containing(raw.x, raw.y, raw.z)).isAir();
        BlockPos ground = (!altHeld && (distance > TOUCH_RANGE || embedded)) ? findGroundBelow(client, raw) : null;

        if (ground != null) {
            return new Result(Vec3.atBottomCenterOf(ground), true, ground);
        }
        return new Result(raw, false, null);
    }

    public static boolean isAltHeld() {
        var window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, InputConstants.KEY_LALT)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RALT);
    }

    /** First solid block straight below `point`, or null if none within GROUND_SCAN_DEPTH (e.g. aiming out over a void) -- caller falls back to the raw, unaligned point. */
    private static BlockPos findGroundBelow(Minecraft client, Vec3 point) {
        BlockPos.MutableBlockPos cursor = BlockPos.containing(point.x, point.y, point.z).mutable();
        int startY = cursor.getY();
        for (int i = 0; i < GROUND_SCAN_DEPTH; i++) {
            cursor.setY(startY - i);
            if (!client.level.getBlockState(cursor).isAir()) {
                return cursor.above().immutable();
            }
        }
        return null;
    }
}
