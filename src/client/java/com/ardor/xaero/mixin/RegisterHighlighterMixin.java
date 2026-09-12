package com.ardor.xaero.mixin;

import com.ardor.xaero.RegionChunkHighlighter;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.WorldMapSession;
import xaero.map.highlight.HighlighterRegistry;

/**
 * Replaces the original tick-polling XaeroIntegration approach, which was fundamentally too late
 * -- confirmed via `javap -c` disassembly of WorldMapSession.init() (the ~20-instruction window
 * where a HighlighterRegistry gets constructed, ONE hardcoded partner mod
 * (SupportOpenPartiesAndClaims) gets a direct call to register into it, then
 * HighlighterRegistry.end() immediately freezes it -- all synchronously, before MapProcessor even
 * exists). By the time WorldMapSession.getCurrentSession().getMapProcessor() is reachable from our
 * own code (confirmed live: threw UnsupportedOperationException, java.util.Collections
 * $UnmodifiableCollection.add), the registry is already permanently frozen -- there is no
 * legitimate post-hoc registration path, generic-looking public register() method
 * notwithstanding.
 *
 * This injects into that exact narrow window instead: right before the end() call, using
 * MixinExtras' @Local (confirmed present in this environment -- mixinextras 0.5.4 is already
 * loaded by Fabric Loader) to capture the HighlighterRegistry local variable without needing to
 * know its bytecode slot number, unlike the fragile slot-index approach the mob-marker
 * investigation ruled out. This is a small, clearly-bounded injection point (register one more
 * highlighter into an about-to-be-frozen list) -- categorically lower-risk than that one.
 */
@Mixin(WorldMapSession.class)
public abstract class RegisterHighlighterMixin {

    @Inject(
            method = "init",
            at = @At(value = "INVOKE", target = "Lxaero/map/highlight/HighlighterRegistry;end()V")
    )
    private void ardor$registerRegionHighlighter(ClientPacketListener connection, long seed, CallbackInfo ci,
                                                         @Local HighlighterRegistry registry) {
        registry.register(new RegionChunkHighlighter());
    }
}
