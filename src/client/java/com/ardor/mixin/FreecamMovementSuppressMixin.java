package com.ardor.mixin;

import com.ardor.client.CameraModeController;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Zeroes the real player's movement/jump/sprint/shift impulse in either non-NORMAL camera mode: FLY
 * repurposes WASD/space/shift for flying the detached camera (FreecamController reads the same
 * options keys directly) instead of ALSO walking/jumping the real player entity underneath it; SIMS
 * has no keyboard-driven movement at all (camera repositioning there is drag-only), so the same
 * suppression just means standing still doesn't accidentally still respond to a stray WASD press.
 */
@Mixin(KeyboardInput.class)
public abstract class FreecamMovementSuppressMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void ardor$suppressWhileFreecam(CallbackInfo ci) {
        if (CameraModeController.mode() == CameraModeController.Mode.NORMAL) return;
        KeyboardInput self = (KeyboardInput) (Object) this;
        self.moveVector = Vec2.ZERO;
        self.keyPresses = Input.EMPTY;
    }
}
