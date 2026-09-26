package com.ardor.client;

import com.ardor.region.Region;
import com.ardor.region.RegionManager;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.SubmitNodeCollector;
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
 * MC 26.3 replaced the old immediate-mode bufferSource()/VertexConsumer path with a submit-node
 * pipeline (confirmed via javap against LevelRenderer's own block-outline code, which is now
 * private void submitHitOutline(...)): push the PoseStack, translate by -camera (world -> camera
 * relative, same math the old ShapeRenderer.renderShape offset params did internally), then hand
 * the still-world-space VoxelShape to SubmitNodeCollector.submitShapeOutline. No more manual
 * VertexConsumer/endBatch -- the collector batches internally.
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
        Vec3 cam = context.gameRenderer().mainCamera().position();
        PoseStack poseStack = context.poseStack();
        SubmitNodeCollector collector = context.submitNodeCollector();

        for (Region region : RegionManager.get().currentProfile().regions.values()) {
            if (region.isGlobal()) continue;
            AABB box = AABB.encapsulatingFullBlocks(
                    new BlockPos(region.minX, region.minY, region.minZ),
                    new BlockPos(region.maxX, region.maxY, region.maxZ));
            drawBox(poseStack, collector, box, cam, regionColor(region.name));
        }

        if (SingleSelectionMode.isActive()) {
            AABB highlight = SingleSelectionMode.currentHighlightBox();
            if (highlight != null) {
                drawBox(poseStack, collector, highlight, cam, 0xFF00FFFF);
            } else {
                Vec3 point = SingleSelectionMode.currentHologramPoint();
                if (point != null) drawBox(poseStack, collector, hologramBox(point), cam, 0xFF00FFFF);
            }
        }

        if (AreaSelectionMode.isActive()) {
            AABB preview = AreaSelectionMode.currentPreviewBox();
            if (preview != null) drawBox(poseStack, collector, preview, cam, 0xFFFFAA00);
        }
    }

    private static AABB hologramBox(Vec3 point) {
        double half = 0.15;
        return new AABB(point.x - half, point.y - half, point.z - half, point.x + half, point.y + half, point.z + half);
    }

    private static void drawBox(PoseStack poseStack, SubmitNodeCollector collector, AABB box, Vec3 cam, int argb) {
        VoxelShape shape = Shapes.create(box);
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        collector.submitShapeOutline(poseStack, shape, RenderTypes.lines(), argb, 1.0f, false);
        poseStack.popPose();
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
