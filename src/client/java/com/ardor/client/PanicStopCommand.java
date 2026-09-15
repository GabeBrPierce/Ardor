package com.ardor.client;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.network.chat.Component;

/** `//ardor stop` -- chat-command backup for PanicStopKey (K by default), in case the key gets rebound/forgotten mid-incident. */
public final class PanicStopCommand {

    private PanicStopCommand() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("ardor")
                        .then(ClientCommands.literal("stop")
                                .executes(ctx -> {
                                    PanicStop.now();
                                    ctx.getSource().sendFeedback(Component.literal("[Ardor] STOPPED"));
                                    return 1;
                                }))));
    }
}
