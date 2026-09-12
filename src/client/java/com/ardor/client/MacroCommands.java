package com.ardor.client;

import com.ardor.macro.MacroRecorder;
import com.ardor.macro.MacroStore;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.network.chat.Component;

/**
 * "I want to be able to record movements and save them as well as their own
 * named macros." Recording is start/stop via chat command (not a held key --
 * a macro can run for a while, "hold a key the whole time" doesn't fit) and
 * needs a name, matching //ardor's WorldEdit-style convention already used for
 * regions:
 *
 *   //ardor record start <name>
 *   //ardor record stop
 *   //ardor record list
 */
public final class MacroCommands {

    private MacroCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("ardor")
                    .then(ClientCommands.literal("record")
                            .then(ClientCommands.literal("start")
                                    .then(ClientCommands.argument("name", StringArgumentType.word())
                                            .executes(ctx -> {
                                                String name = StringArgumentType.getString(ctx, "name");
                                                MacroRecorder.start(name);
                                                ctx.getSource().sendFeedback(Component.literal("[Ardor] recording macro '" + name + "'"));
                                                return 1;
                                            })))
                            .then(ClientCommands.literal("stop")
                                    .executes(ctx -> {
                                        MacroRecorder.stop();
                                        ctx.getSource().sendFeedback(Component.literal("[Ardor] macro saved"));
                                        return 1;
                                    }))
                            .then(ClientCommands.literal("list")
                                    .executes(ctx -> {
                                        var names = MacroStore.list();
                                        ctx.getSource().sendFeedback(Component.literal(
                                                "[Ardor] macros: " + (names.isEmpty() ? "(none)" : String.join(", ", names))));
                                        return 1;
                                    }))));
        });
    }
}
