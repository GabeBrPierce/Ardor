package com.ardor.xaero.mixin;

import com.ardor.bridge.BaritoneNav;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.gui.GuiMap;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

import java.util.ArrayList;

/**
 * Adds a "Go Here" entry to Xaero's World Map right-click menu -- confirmed via javap disassembly
 * that GuiMap.getRightClickOptions() builds its ArrayList entirely from hardcoded checks against
 * specific known partner mods (SupportMods.minimap()/etc), not a registered-provider list, so
 * there's no clean non-Mixin way to add our own entry. Injecting at the tail of this one method is
 * about as low-risk as a Mixin gets: it only appends to an already-built, already-mutable list
 * right before it's returned, without touching any of Xaero's own internal logic or state.
 *
 * rightClickX/Y/Z are private fields on GuiMap holding the world coordinates of whatever spot was
 * right-clicked (@Shadow gives this mixin direct access regardless of original visibility -- the
 * standard, intended use of @Shadow, not a workaround). Only reachable at all when Xaero's World
 * Map is installed (see the mixin config's target class and ArdorClient's isModLoaded guard
 * around registering the config in the first place).
 */
@Mixin(GuiMap.class)
public abstract class GoHereMixin {

    @Shadow private int rightClickX;
    @Shadow private int rightClickY;
    @Shadow private int rightClickZ;

    @Inject(method = "getRightClickOptions", at = @At("RETURN"))
    private void ardor$addGoHere(CallbackInfoReturnable<ArrayList<RightClickOption>> cir) {
        ArrayList<RightClickOption> options = cir.getReturnValue();
        // rightClickY is the clicked GROUND block itself (confirmed live: without the +1, Baritone's
        // GoalBlock targeted a solid block, so it had to dig through it to "arrive" -- which also
        // triggered Baritone's own autoTool setting to keep re-equipping whatever it considered the
        // best digging tool, hijacking the hotbar mid-travel). +1 targets the walkable air block
        // standing on top of that ground block instead, which needs no digging for ordinary terrain.
        int x = rightClickX, y = rightClickY + 1, z = rightClickZ;
        options.add(new RightClickOption("Go Here (Ardor)", options.size(), (IRightClickableElement) (Object) this) {
            @Override
            public void onAction(Screen screen) {
                BaritoneNav.goTo(x, y, z);
            }

            @Override
            public Component getDisplayName() {
                return Component.literal("Go Here (Ardor)");
            }
        });
    }
}
