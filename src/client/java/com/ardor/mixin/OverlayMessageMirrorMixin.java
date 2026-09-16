package com.ardor.mixin;

import com.ardor.client.HudManager;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mirrors every action-bar message into the chat log (HudManager.onOverlayMessageSet, gated by
 * ArdorConfig.hudMirrorActionBarToChat) so a message that would otherwise vanish after 60 ticks
 * stays readable.
 *
 * setOverlayMessage is the single funnel confirmed via javap against the real 26.1.2 client jar --
 * ChatListener.handleOverlay (server ActionBar packets and Player.sendOverlayMessage both land
 * there), Gui's own internal call, and this mod's HudManager.setActionBarText all go through it.
 * Injecting here rather than widening the fields because this is new behavior on a call, not
 * visibility -- the one thing in the HUD subsystem that genuinely needs a mixin.
 */
@Mixin(Gui.class)
public abstract class OverlayMessageMirrorMixin {

    @Inject(method = "setOverlayMessage", at = @At("HEAD"))
    private void ardor$mirrorOverlayMessageToChat(Component text, boolean animateColor, CallbackInfo ci) {
        HudManager.onOverlayMessageSet(text);
    }
}
