package com.ardor.game;

import com.ardor.bridge.BaritoneNav;
import com.ardor.region.RegionManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * "Add a flag for regions DisableImplicitDestruction that disables the mod deciding 'we should
 * break these blocks to get to the target.'" BlockWorldMovement's own dig-through-obstacles logic
 * checks this per-position directly (the old custom pathfinder, still used for goto/shaft/digHole),
 * but Baritone -- which drives most movement now (mine's walk, follow, tool-fetch, Go Here) -- has
 * no notion of Ardor's region system at all; its own independent "break an inconvenient block
 * along the path" decision (Settings.allowBreak) needed a separate bridge. Every tick, syncs that
 * setting to whether the player is CURRENTLY standing in a region with disableImplicitDestruction
 * set -- checked continuously (not just once when a path starts) since a region boundary can be
 * crossed mid-path.
 *
 * Deliberately does NOT touch BaritoneNav.configure()'s other settings (allowPlace, autoTool,
 * etc.) -- only allowBreak, the one setting this flag is actually about.
 */
public final class BaritoneRegionGate {

    private BaritoneRegionGate() {}

    private static Boolean lastAppliedValue; // null until the first tick actually sets it -- avoids writing the same value every single tick

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick(client);
            } catch (RuntimeException e) {
                System.err.println("[ardor] baritone region gate failed: " + e);
            }
        });
    }

    private static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return;
        boolean disabled = RegionManager.get().hasFlag(
                RegionManager.currentProfileKey(), player.blockPosition(), r -> r.disableImplicitDestruction);
        boolean allow = !disabled;
        if (lastAppliedValue != null && lastAppliedValue == allow) return;
        BaritoneNav.setAllowBreak(allow);
        lastAppliedValue = allow;
    }
}
