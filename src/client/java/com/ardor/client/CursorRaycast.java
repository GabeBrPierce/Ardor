package com.ardor.client;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Casts a ray from an arbitrary SCREEN cursor position instead of the crosshair (screen center) --
 * what Sims mode's free-floating cursor needs to know what's "under the cursor," since vanilla's own
 * client.hitResult is always a center-of-screen raycast and has no notion of an offset cursor.
 *
 * Unprojects the cursor's screen position into a world-space ray direction using the SAME rotation
 * convention Minecraft's own Camera uses (confirmed against a real reference implementation --
 * MinecraftFreecam/Freecam's FreecamPosition.setRotation comments this exact quaternion construction
 * as "From net.minecraft.client.render.Camera.setRotation": rotationYXZ(-yaw, pitch, 0)), then does
 * its own block clip (Level.clip, vanilla's real raycast primitive) plus a manual nearby-entity
 * ray/AABB test (AABB.clip) since there's no vanilla entry point for "raycast against entities from
 * an arbitrary direction" the way client.hitResult's crosshair pick has internally.
 */
public final class CursorRaycast {

    private static final double MAX_DISTANCE = 32.0;
    private static final double ENTITY_SEARCH_PADDING = 2.0;

    private CursorRaycast() {}

    /** Null only if there's no level/player loaded. Otherwise always a real HitResult (MISS type if nothing in range). */
    public static HitResult cast(Vec3 origin, float yaw, float pitch, double cursorX, double cursorY) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || mc.player == null) return null;

        Vec3 direction = directionThroughCursor(mc, yaw, pitch, cursorX, cursorY);
        Vec3 end = origin.add(direction.scale(MAX_DISTANCE));

        BlockHitResult blockHit = level.clip(new ClipContext(origin, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        double blockDistSq = blockHit.getType() == HitResult.Type.MISS
                ? Double.MAX_VALUE
                : blockHit.getLocation().distanceToSqr(origin);

        EntityHitResult entityHit = pickEntity(level, origin, end, mc.player, blockDistSq);
        return entityHit != null ? entityHit : blockHit;
    }

    private static EntityHitResult pickEntity(Level level, Vec3 origin, Vec3 end, Entity exclude, double maxDistSq) {
        AABB searchBox = new AABB(origin, end).inflate(ENTITY_SEARCH_PADDING);
        Entity closest = null;
        Vec3 closestPoint = null;
        double closestDistSq = maxDistSq;

        for (Entity entity : level.getEntities(EntityTypeTest.forClass(Entity.class), searchBox, e -> e != exclude && e.isPickable())) {
            var hit = entity.getBoundingBox().inflate(entity.getPickRadius()).clip(origin, end);
            if (hit.isEmpty()) continue;
            double distSq = hit.get().distanceToSqr(origin);
            if (distSq < closestDistSq) {
                closestDistSq = distSq;
                closest = entity;
                closestPoint = hit.get();
            }
        }
        return closest != null ? new EntityHitResult(closest, closestPoint) : null;
    }

    private static Vec3 directionThroughCursor(Minecraft mc, float yaw, float pitch, double cursorX, double cursorY) {
        double screenW = mc.getWindow().getGuiScaledWidth();
        double screenH = mc.getWindow().getGuiScaledHeight();
        double ndcX = (cursorX / screenW) * 2 - 1;
        double ndcY = 1 - (cursorY / screenH) * 2;

        Camera camera = mc.gameRenderer.mainCamera();
        double tanHalfFovY = Math.tan(Math.toRadians(camera.getFov()) / 2);
        double aspect = screenW / screenH;

        Vector3f cameraSpace = new Vector3f((float) (ndcX * tanHalfFovY * aspect), (float) (ndcY * tanHalfFovY), -1f).normalize();
        Quaternionf rotation = new Quaternionf().rotationYXZ((float) Math.toRadians(-yaw), (float) Math.toRadians(pitch), 0f);
        Vector3f world = rotation.transform(cameraSpace);
        return new Vec3(world.x, world.y, world.z).normalize();
    }
}
