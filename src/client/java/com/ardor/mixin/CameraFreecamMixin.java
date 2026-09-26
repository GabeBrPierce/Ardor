package com.ardor.mixin;

import com.ardor.client.FreecamController;
import com.ardor.client.SimsCameraController;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Overrides the camera's position AND rotation for BOTH non-NORMAL camera modes, applied after
 * vanilla's own entity-aligned update:
 *   - FLY: position interpolated between the previous and current tick (FreecamController's own
 *     tick rate is 20/s, this injection runs once per RENDER frame -- interpolating is what makes
 *     movement smooth instead of visibly stepping); rotation from FreecamController's own yaw/pitch,
 *     accumulated via FreecamTurnMixin's redirect since the entity's own rotation is never touched.
 *   - SIMS: position is just SimsCameraController's own (only ever changes on a drag, no tick
 *     interpolation needed); rotation likewise from its own yaw/pitch, driven by middle-drag. This is
 *     also where SimsCameraController.onRenderFrame() gets called -- once per frame, right before its
 *     position/rotation are read, so the free cursor's raycast target is always current.
 */
@Mixin(Camera.class)
public abstract class CameraFreecamMixin {

    @Shadow protected abstract void setPosition(Vec3 pos);

    @Shadow protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "update", at = @At("TAIL"))
    private void ardor$override(DeltaTracker tracker, CallbackInfo ci) {
        if (FreecamController.isActive()) {
            float partialTick = tracker.getGameTimeDeltaPartialTick(true);
            setPosition(FreecamController.renderPosition(partialTick));
            setRotation(FreecamController.yaw(), FreecamController.pitch());
        } else if (SimsCameraController.isActive()) {
            SimsCameraController.onRenderFrame();
            setPosition(SimsCameraController.position());
            setRotation(SimsCameraController.yaw(), SimsCameraController.pitch());
        }
    }
}
