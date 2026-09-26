package com.ardor.mixin;

import com.ardor.client.ArdorWheelScreen;
import com.ardor.client.CameraModeController;
import com.ardor.client.FreecamController;
import com.ardor.client.FreecamOrchestratorOverlay;
import com.ardor.client.SimsCameraController;
import com.ardor.client.SingleSelectionMode;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The ONE place every real mouse click gets routed for BOTH non-NORMAL camera modes -- deliberately
 * unified rather than one mixin per mode: two separate cancelling @Inject(HEAD)s on the same vanilla
 * method can't safely coexist (whichever cancels first silently prevents the other from ever
 * running), so FLY and SIMS routing both have to live in this one injector, branching on
 * CameraModeController.mode().
 *
 * Confirmed via javap -c against the real 26.3 MouseHandler.onButton bytecode, not assumed: when
 * Gui.screen() == null (real gameplay, our exact scenario), onButton's ENTIRE effect on gameplay is
 * one final unconditional block that calls KeyMapping.set(...)/KeyMapping.click(...) for whichever
 * button was pressed -- that's the only place mouse-bound KeyMappings like keyAttack/keyUse ever get
 * updated, which is what Minecraft.handleKeybinds() polls every tick to actually swing/mine/use.
 * Cancelling at HEAD skips that block entirely, for every button, so attack/mine/use/place are
 * suppressed in both modes -- neither a flying spectator camera nor a menu-driven point-and-click
 * mode should be able to accidentally swing at or place into the world.
 *
 * Button numbering is NOT raw GLFW -- confirmed via javap against this project's real InputConstants
 * class that MOUSE_BUTTON_LEFT/MIDDLE/RIGHT are 1/2/3 here, not GLFW's own 0/1/2 (an earlier version
 * of this used the GLFW convention and silently never matched a real left click as a result).
 */
@Mixin(MouseHandler.class)
public abstract class FreecamClickMixin {

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void ardor$route(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
        CameraModeController.Mode mode = CameraModeController.mode();
        if (mode == CameraModeController.Mode.NORMAL) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null) return;

        if (mode == CameraModeController.Mode.FLY) {
            routeFly(mc, button, action);
        } else {
            routeSims(mc, button, action);
        }
        ci.cancel();
    }

    /** Every click is a click on the FreecamOrchestratorOverlay's cards -- see that class's own doc. */
    private void routeFly(Minecraft mc, MouseButtonInfo button, int action) {
        if (button.button() != InputConstants.MOUSE_BUTTON_LEFT || action != InputConstants.PRESS) return;
        MouseHandler self = (MouseHandler) (Object) this;
        int mouseX = (int) self.getScaledXPos(mc.getWindow());
        int mouseY = (int) self.getScaledYPos(mc.getWindow());
        // GLFW_MOD_SHIFT = 0x0001 (modifier bitmask IS the standard GLFW one -- MouseButtonInfo only
        // remaps the button index, not the modifier bits) -- shift-click multi-selects, a plain
        // click single-selects.
        boolean shiftHeld = (button.modifiers() & 0x0001) != 0;
        FreecamOrchestratorOverlay.handleClick(mouseX, mouseY, shiftHeld);
    }

    /**
     * Left button: press starts a click/drag distinction (SimsCameraController.onRenderFrame decides
     * once the cursor has moved far enough), release either does nothing further (it was a drag,
     * already panned continuously) or -- reserved for a future plain-click action, "select" today
     * just means "the cube under the cursor," which already updates continuously with no click
     * needed. Middle button: held state read every frame by SimsCameraController to drive rotation.
     * Right button: opens the contextual wheel for whatever's under the cursor right now, via
     * SingleSelectionMode's already-active (started on entering Sims mode) target-tracking state.
     */
    private void routeSims(Minecraft mc, MouseButtonInfo button, int action) {
        MouseHandler self = (MouseHandler) (Object) this;
        double x = self.getScaledXPos(mc.getWindow());
        double y = self.getScaledYPos(mc.getWindow());

        if (button.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            if (action == InputConstants.PRESS) {
                SimsCameraController.onLeftPress(x, y);
            } else if (action == InputConstants.RELEASE) {
                SimsCameraController.onLeftRelease();
            }
        } else if (button.button() == InputConstants.MOUSE_BUTTON_MIDDLE) {
            SimsCameraController.setMiddleDown(action == InputConstants.PRESS);
        } else if (button.button() == InputConstants.MOUSE_BUTTON_RIGHT && action == InputConstants.PRESS) {
            // Diagnostic print, deliberately left in: earlier live feedback said right-click "isn't
            // working" and no code-level bug was found to explain it by re-reading this path -- if
            // that's still true after this build, this line confirms (or rules out) whether this
            // branch is even being reached at all, which the previous version gave no way to check.
            System.err.println("[ardor] Sims mode right-click: opening wheel, currentSubWheelOptions=" + SingleSelectionMode.currentSubWheelOptions());
            java.util.List<ArdorWheelScreen.WheelOption> options = SingleSelectionMode.currentSubWheelOptions();
            mc.gui.setScreen(new ArdorWheelScreen(options));
        }
    }
}
