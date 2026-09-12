package com.ardor.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Unconditionally swallows vanilla's own automatic pick-block call inside handleKeybinds() --
 * confirmed via javap disassembly of the real MC 26.1.2 client jar that this is the single call
 * site (Minecraft.handleKeybinds() polls options.keyPickItem.consumeClick() and calls
 * Minecraft.pickBlockOrEntity(), both private), running mid-tick, BEFORE Fabric's
 * ClientTickEvents.END_CLIENT_TICK (where PickWheelKey does its own tap/hold timing). That
 * ordering means a conditional "let it through on a plain tap" redirect can't work either -- by
 * the time our own tick hook could react to a fresh press, vanilla's handleKeybinds() for that
 * same tick has already run. So this redirect always does nothing, full stop, and PickWheelKey is
 * the sole place that ever calls pickBlockOrEntity() now (via the access-widened method, on tap
 * release) -- see its class doc.
 */
@Mixin(Minecraft.class)
public abstract class PickBlockMixin {

    @Redirect(method = "handleKeybinds", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;pickBlockOrEntity()V"))
    private void ardor$suppressVanillaPickBlock(Minecraft instance) {
        // no-op -- see class doc
    }
}
