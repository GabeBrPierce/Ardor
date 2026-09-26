package com.ardor.mixin;

import com.ardor.client.ActivityTracker;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Resets ActivityTracker's human-input timer on every real key event.
 *
 * keyPress(long, int, KeyEvent) is the sole landing point for the GLFW key callback in 26.1.2
 * (confirmed via javap: setup() hands InputConstants.setupKeyboardCallbacks a GLFWKeyCallbackI
 * that builds a KeyEvent and defers to keyPress via Minecraft.execute). Note the signature is
 * NOT vanilla folklore's keyPress(long,int,int,int,int) any more -- key/scancode/mods are packed
 * into the KeyEvent record and the remaining int is the GLFW action.
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardActivityMixin {

    @Inject(method = "keyPress", at = @At("HEAD"))
    private void ardor$markRealKeyInput(long window, int action, KeyEvent keyEvent, CallbackInfo ci) {
        ActivityTracker.onRawInput();
    }
}
