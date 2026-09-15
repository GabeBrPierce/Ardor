package com.ardor.game;

import baritone.api.BaritoneAPI;
import com.ardor.bridge.BaritoneNav;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * "We are STILL not selecting the correct tool for the job -- watching dirt get broken by a sword
 * when we have a shovel right there, even stone broken with a sword." Real gap, found by reading
 * the code, not the same bug the earlier hotbar-fighting fix (BaritoneNav.configure's own doc)
 * already addressed: that fix set `assumeExternalAutoTool = true` specifically so Baritone stops
 * doing ITS OWN tool selection and trusts this mod to have already equipped the right thing -- but
 * nothing actually does that for the blocks Baritone decides to break AUTONOMOUSLY while just
 * walking a path (clearing an obstacle in its way). ToolSelector.equipBestTool only ever gets
 * called for the one DELIBERATE target block a real dig command hands to BlockBreaker
 * (BlockBreaker.breakBlock) -- Baritone's own incidental path-clearing breaks whatever's currently
 * held, since `autoTool=false` turned off its own selection and told it "trust the external one"
 * that, for this specific case, never existed.
 *
 * Fix: while Baritone is actively pathing with block-breaking allowed, keep the best tool for
 * whatever block is directly in the crosshair equipped, every tick -- the same block Baritone's
 * own path execution is about to (or currently) interacting with, since it look-locks toward its
 * next move the same way a real player would. Gated off during combat (GameActionController.
 * isBusy(), which is also true while attackUntilDead's own BaritoneNav.followEntity chase is
 * active) so this never fights that path's own weapon choice.
 *
 * Approximation, not a guarantee: Baritone's own path-node target isn't exposed cleanly enough
 * (nothing public on IPathingBehavior gives "the exact block about to be broken") to target
 * precisely, so "whatever's in the crosshair right now" is the closest available proxy. Idempotent
 * either way -- ToolSelector.equipBestTool is a no-op if the current tool already qualifies, so
 * this can't fight BlockBreaker's own equip for the same block, only fill the gap BlockBreaker
 * never covered.
 */
public final class BaritoneAutoToolController {

    private BaritoneAutoToolController() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick(client);
            } catch (RuntimeException e) {
                System.err.println("[ardor] baritone auto-tool failed: " + e);
            }
        });
    }

    private static BlockPos lastCheckedPos; // skip re-checking the same crosshair target every tick -- equipBestTool only needs to run again once it actually changes

    private static void tick(Minecraft client) {
        if (!BaritoneNav.isPathing() || GameActionController.isBusy()) {
            lastCheckedPos = null;
            return;
        }
        if (!BaritoneAPI.getSettings().allowBreak.value) return;

        LocalPlayer player = client.player;
        Level level = client.level;
        if (player == null || level == null) return;

        HitResult hit = client.hitResult;
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) return;

        BlockPos pos = blockHit.getBlockPos();
        if (pos.equals(lastCheckedPos)) return;
        lastCheckedPos = pos;

        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return;
        ToolSelector.equipBestTool(player, state);
    }
}
