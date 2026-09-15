package com.ardor.client;

import com.ardor.home.HomeCommand;
import com.ardor.home.HomeCommandRunner;
import com.ardor.home.HomeCommandStore;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.network.chat.Component;

/**
 * WorldEdit-style //ardor home commands, matching StructCommands/MacroCommands' own convention.
 * "Servers provide commands to set home... /home kitchen ... will automatically teleport your
 * player to a specific location if we hold still for X amount of seconds" -- this teaches the bot
 * that command plus how long to hold still afterward, then reuses that whenever asked to go there.
 *
 *   //ardor home teach <name> <holdSeconds> <command...>  -- e.g. "teach kitchen 3 home kitchen"
 *   //ardor home go <name>
 *   //ardor home list
 */
public final class HomeCommands {

    private HomeCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("ardor")
                    .then(ClientCommands.literal("home")
                            .then(ClientCommands.literal("teach")
                                    .then(ClientCommands.argument("name", StringArgumentType.word())
                                            .then(ClientCommands.argument("holdSeconds", IntegerArgumentType.integer(0))
                                                    .then(ClientCommands.argument("command", StringArgumentType.greedyString())
                                                            .executes(ctx -> {
                                                                String name = StringArgumentType.getString(ctx, "name");
                                                                int holdSeconds = IntegerArgumentType.getInteger(ctx, "holdSeconds");
                                                                String command = StringArgumentType.getString(ctx, "command");
                                                                HomeCommandStore.save(new HomeCommand(name, command, holdSeconds));
                                                                ctx.getSource().sendFeedback(Component.literal(
                                                                        "[Ardor] taught home command '" + name + "' -> \"" + command + "\" (hold " + holdSeconds + "s)"));
                                                                return 1;
                                                            })))))
                            .then(ClientCommands.literal("go")
                                    .then(ClientCommands.argument("name", StringArgumentType.word())
                                            .executes(ctx -> {
                                                String name = StringArgumentType.getString(ctx, "name");
                                                var source = ctx.getSource();
                                                source.sendFeedback(Component.literal("[Ardor] going home: " + name));
                                                HomeCommandRunner.go(name,
                                                        () -> source.sendFeedback(Component.literal("[Ardor] home '" + name + "' done")),
                                                        error -> source.sendFeedback(Component.literal("[Ardor] home '" + name + "' failed: " + error)));
                                                return 1;
                                            })))
                            .then(ClientCommands.literal("list")
                                    .executes(ctx -> {
                                        var names = HomeCommandStore.list();
                                        ctx.getSource().sendFeedback(Component.literal(
                                                "[Ardor] home commands: " + (names.isEmpty() ? "(none)" : String.join(", ", names))));
                                        return 1;
                                    }))));
        });
    }
}
