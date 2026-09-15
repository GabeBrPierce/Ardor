package com.ardor.client;

import com.ardor.script.ScriptEngine;
import com.ardor.script.ScriptStore;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.network.chat.Component;

/**
 * WorldEdit-style //ardor script commands, matching StructCommands/MacroCommands/RegionCommands'
 * own convention:
 *
 *   //ardor script run <name>   -- runs config/ardor-scripts/<name>.lua (ScriptEngine)
 *   //ardor script stop         -- cancels whatever script is currently running
 *   //ardor script list
 *
 * There is deliberately no //ardor script save/edit command: a chat command can't reasonably
 * carry a multi-line Lua source body, so scripts are hand-authored (or LLM-authored, then pasted
 * in) as plain .lua files under config/ardor-scripts/ -- the same "hand-edit the file" convention
 * config/ardor.json already uses for anything too large/structured for an in-game form.
 */
public final class ScriptCommands {

    private ScriptCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("ardor")
                    .then(ClientCommands.literal("script")
                            .then(ClientCommands.literal("run")
                                    .then(ClientCommands.argument("name", StringArgumentType.word())
                                            .executes(ctx -> {
                                                String name = StringArgumentType.getString(ctx, "name");
                                                runScript(ctx.getSource(), name);
                                                return 1;
                                            })))
                            .then(ClientCommands.literal("stop")
                                    .executes(ctx -> {
                                        ScriptEngine.cancel();
                                        ctx.getSource().sendFeedback(Component.literal("[Ardor] script stopped"));
                                        return 1;
                                    }))
                            .then(ClientCommands.literal("list")
                                    .executes(ctx -> {
                                        var names = ScriptStore.list();
                                        ctx.getSource().sendFeedback(Component.literal(
                                                "[Ardor] scripts: " + (names.isEmpty() ? "(none)" : String.join(", ", names))));
                                        return 1;
                                    }))));
        });
    }

    private static void runScript(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, String name) {
        String src;
        try {
            src = ScriptStore.load(name);
        } catch (Exception e) {
            source.sendFeedback(Component.literal("[Ardor] " + e.getMessage()));
            return;
        }
        source.sendFeedback(Component.literal("[Ardor] running script '" + name + "'"));
        ScriptEngine.run(src, name, error ->
                net.minecraft.client.Minecraft.getInstance().execute(() ->
                        source.sendFeedback(Component.literal("[Ardor] script '" + name + "' failed: " + error))));
    }
}
