package com.ardor.mixin;

import com.ardor.client.ActivityTracker;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Resets ActivityTracker's human-input timer on every real mouse button and every real mouse move.
 * Camera look counts as input: nothing in this mod turns the player through MouseHandler --
 * BaritoneFacingController and friends call player.setYRot/setXRot on the entity directly -- so a
 * cursor-position callback can only have come from a hand on the mouse.
 *
 * onMove injects at TAIL rather than HEAD on purpose. onMove has two early returns (a callback for
 * some other window, and the ignoreFirstMove swallow that grabMouse/releaseMouse arms precisely
 * because re-centering the cursor fires a synthetic move), and TAIL binds to the last RETURN, so
 * the one genuinely non-human path through this method can't reset the timer.
 */
@Mixin(MouseHandler.class)
public abstract class MouseActivityMixin {

    @Inject(method = "onButton", at = @At("HEAD"))
    private void ardor$markRealMouseButton(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
        ActivityTracker.onRawInput();
    }

    @Inject(method = "onMove", at = @At("TAIL"))
    private void ardor$markRealMouseMove(long window, double x, double y, CallbackInfo ci) {
        ActivityTracker.onRawInput();
    }
}
