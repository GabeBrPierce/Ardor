package com.ardor.home;

import com.ardor.game.PathfindingController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.function.Consumer;

/**
 * Runs a taught HomeCommand: sends the real server/client command, then holds the player
 * perfectly still for holdSeconds afterward so a hold-still-triggered server-side teleport (the
 * exact case that prompted this -- "/home kitchen ... will automatically teleport your player to
 * a specific location if we hold still for X amount of seconds") actually has the chance to fire,
 * instead of the bot immediately wandering off and cancelling it. Reuses
 * PathfindingController.pauseAllMovementAndActions -- the same "wait-stopmoving-duration"
 * primitive requested for the scripting language, since holding still is exactly what this needs.
 */
public final class HomeCommandRunner {

    private HomeCommandRunner() {}

    public static void go(String name, Runnable onDone, Consumer<String> onFailed) {
        HomeCommand home;
        try {
            home = HomeCommandStore.load(name);
        } catch (RuntimeException e) {
            onFailed.accept(e.getMessage());
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.connection == null) {
            onFailed.accept("no client player loaded");
            return;
        }
        String command = home.command.startsWith("/") ? home.command.substring(1) : home.command;
        player.connection.sendCommand(command);
        PathfindingController.pauseAllMovementAndActions(home.holdSeconds * 20, onDone);
    }
}
