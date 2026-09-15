package com.ardor.game;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * "We need to work on only targeting entities within line of sight. I am looking at mobs through
 * walls which is suspicious." Extracted from ProactiveCombatController's own already-verified
 * check (that controller already gated its strike-first attack on this) so SelectorResolver's
 * broad `@e[category=...]` scans -- used by the default auto-defend binding and Kill All, exactly
 * the "pick the nearest hostile in a radius" pattern that let a wall-blocked mob get targeted --
 * can share the same real check instead of a second copy.
 */
public final class LineOfSight {

    private LineOfSight() {}

    /**
     * Level.clip(ClipContext), confirmed via javap against the real jar (BlockGetter.clip,
     * inherited by Level/ClientLevel): a block-only raycast from eye to eye. ClipContext.Block.
     * COLLIDER checks real collision shapes (glass panes, leaves, etc. block sight the way a
     * player's eye actually would); Fluid.NONE means water doesn't block sight. The five-arg
     * constructor taking an Entity (rather than a raw CollisionContext) is the standard vanilla
     * shape for "raycast as if this entity were looking" and lets us pass the player directly.
     * HitResult.Type.MISS means nothing solid was in the way -- clear line of sight.
     */
    public static boolean hasLineOfSight(LocalPlayer self, Entity target) {
        Vec3 from = self.getEyePosition();
        Vec3 to = target.getEyePosition();
        ClipContext ctx = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self);
        BlockHitResult hit = self.level().clip(ctx);
        return hit.getType() == HitResult.Type.MISS;
    }
}
