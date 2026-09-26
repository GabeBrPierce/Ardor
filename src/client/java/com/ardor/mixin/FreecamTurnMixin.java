package com.ardor.mixin;

import com.ardor.client.CameraModeController;
import com.ardor.client.FreecamController;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Redirects mouse-look to FreecamController instead of the real player entity while freecam is
 * active -- the fix for two bugs the previous approach (let turnPlayer keep rotating the player,
 * then reset the player's rotation fields back to a frozen snapshot every render frame) actually
 * caused: resetting yRot/xRot before the NEXT frame's mouse delta arrived meant every frame's turn
 * was computed relative to the frozen snapshot instead of accumulating, so look direction could
 * barely move at all ("can't change where I'm looking" -- confirmed live). Cancelling the turn at
 * its real source instead means the player's rotation is never touched in the first place, no
 * resetting needed, and freecam's own yaw/pitch accumulate exactly like the player's would have.
 *
 * Approach taken from MinecraftFreecam/Freecam's own EntityMixin.onChangeLookDirection (MIT,
 * https://github.com/MinecraftFreecam/Freecam) -- Entity.turn(double, double) is the real method
 * MouseHandler.turnPlayer ultimately calls with an already-sensitivity-scaled delta (confirmed via
 * javap -c: turn's own body does `setYRot(getYRot() + p1*0.15f)`/`setXRot(getXRot() + p2*0.15f,
 * clamped to +-90)`), so mirroring that exact math in FreecamController.onTurn is what makes freecam
 * rotation feel identical to normal mouse-look, just aimed at a different target.
 */
@Mixin(Entity.class)
public abstract class FreecamTurnMixin {

    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void ardor$redirectTurn(double yRotDelta, double xRotDelta, CallbackInfo ci) {
        CameraModeController.Mode mode = CameraModeController.mode();
        if (mode == CameraModeController.Mode.NORMAL) return;
        if ((Entity) (Object) this != Minecraft.getInstance().player) return;
        // SIMS mode's cursor is free (not grabbed) so vanilla mouse-look shouldn't even reach here --
        // but if it somehow does, the player must still never rotate; SIMS's own yaw/pitch are driven
        // by middle-drag (SimsCameraController), not this redirect, so simply cancel and drop it.
        if (mode == CameraModeController.Mode.FLY) {
            FreecamController.onTurn(yRotDelta, xRotDelta);
        }
        ci.cancel();
    }
}
