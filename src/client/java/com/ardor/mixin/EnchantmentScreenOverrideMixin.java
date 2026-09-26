package com.ardor.mixin;

import com.ardor.enchant.EnchantRequestScreen;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Swaps vanilla's EnchantmentScreen for EnchantRequestScreen whenever the player opens a real
 * enchanting table. MenuScreens.register() throws IllegalStateException on a second registration
 * for the same MenuType (confirmed via javap on the 26.1.2 client jar -- SCREENS.put returning
 * non-null triggers it), so this intercepts getConstructor's return instead of re-registering,
 * leaving vanilla's own SCREENS map and every other menu type untouched.
 */
@Mixin(MenuScreens.class)
public abstract class EnchantmentScreenOverrideMixin {

    @Inject(method = "getConstructor", at = @At("RETURN"), cancellable = true)
    private static void ardor$useRequestScreen(MenuType<?> type, CallbackInfoReturnable<MenuScreens.ScreenConstructor> cir) {
        if (type == MenuType.ENCHANTMENT) {
            cir.setReturnValue((MenuScreens.ScreenConstructor<EnchantmentMenu, EnchantRequestScreen>) EnchantRequestScreen::new);
        }
    }
}
