package com.ardor.client;

import com.ardor.region.Region;
import com.ardor.region.RegionManager;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * "I want to see this stuff as if they were in the world (wireframe borders of regions, pings)."
 * Draws a wireframe box for every non-global region in the current profile, always on, plus
 * whatever SingleSelectionMode is currently showing (an entity/container highlight box, or a
 * small box marking the hologram point) while that mode is active, plus AreaSelectionMode's
 * growing preview box (a distinct orange, vs. Single Selection's cyan) while IT's active -- the
 * two are mutually exclusive, so at most one of the two shows at once. No logic of its own --
 * purely reads RegionManager/SingleSelectionMode/AreaSelectionMode's existing state, matching the
 * original plan's "region wireframe rendering... draws whatever box list it's told to" description.
 *
 * LevelRenderEvents (not the older WorldRenderEvents, which no longer exists in this Fabric API
 * version -- confirmed via jar inspection, this MC version restructured level rendering into a
 * separate extraction/render-state-object pipeline) gives poseStack()/bufferSource() during
 * AFTER_TRANSLUCENT_TERRAIN, the same immediate-mode entry point vanilla's own block-outline
 * rendering uses. ShapeRenderer.renderShape's x/y/z params are the camera-relative offset to
 * translate an already-world-space VoxelShape by, not a poseStack push.
 */
public final class RegionRenderer {

    private RegionRenderer() {}

    public static void register() {
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(RegionRenderer::render);
    }

    private static void render(LevelRenderContext context) {
        try {
            renderInner(context);
        } catch (RuntimeException e) {
            System.err.println("[ardor] region renderer failed: " + e);
        }
    }

    private static void renderInner(LevelRenderContext context) {
        Vec3 cam = context.gameRenderer().getMainCamera().position();
        MultiBufferSource.BufferSource buffers = context.bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderTypes.lines());

        for (Region region : RegionManager.get().currentProfile().regions.values()) {
            if (region.isGlobal()) continue;
            AABB box = AABB.encapsulatingFullBlocks(
                    new BlockPos(region.minX, region.minY, region.minZ),
                    new BlockPos(region.maxX, region.maxY, region.maxZ));
            drawBox(context, lines, box, cam, regionColor(region.name));
        }

        if (SingleSelectionMode.isActive()) {
            AABB highlight = SingleSelectionMode.currentHighlightBox();
            if (highlight != null) {
                drawBox(context, lines, highlight, cam, 0xFF00FFFF);
            } else {
                Vec3 point = SingleSelectionMode.currentHologramPoint();
                if (point != null) drawBox(context, lines, hologramBox(point), cam, 0xFF00FFFF);
            }
        }

        if (AreaSelectionMode.isActive()) {
            AABB preview = AreaSelectionMode.currentPreviewBox();
            if (preview != null) drawBox(context, lines, preview, cam, 0xFFFFAA00);
        }

        buffers.endBatch(RenderTypes.lines());
    }

    private static AABB hologramBox(Vec3 point) {
        double half = 0.15;
        return new AABB(point.x - half, point.y - half, point.z - half, point.x + half, point.y + half, point.z + half);
    }

    private static void drawBox(LevelRenderContext context, VertexConsumer lines, AABB box, Vec3 cam, int argb) {
        VoxelShape shape = Shapes.create(box);
        ShapeRenderer.renderShape(context.poseStack(), lines, shape, -cam.x, -cam.y, -cam.z, argb, 1.0f);
    }

    /** Stable per-name color so the same region always looks the same across frames/sessions. */
    private static int regionColor(String name) {
        int hash = name.hashCode();
        int r = 128 + (Math.abs(hash) % 128);
        int g = 128 + (Math.abs(hash / 7) % 128);
        int b = 128 + (Math.abs(hash / 13) % 128);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
