package com.ardor.client;

import com.ardor.struct.BuildPlanner;
import com.ardor.struct.LitematicaImporter;
import com.ardor.struct.PlacementRecorder;
import com.ardor.struct.StructBuilder;
import com.ardor.struct.StructFile;
import com.ardor.struct.StructStore;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * WorldEdit-style //ardor struct commands, matching MacroCommands/RegionCommands' own convention:
 *
 *   //ardor struct record start <name>   -- record real placements while building normally
 *   //ardor struct record stop
 *   //ardor struct import <file> <name>  -- <file>.litematic from config/ardor-structs/import/
 *   //ardor struct build <name>          -- build at the player's current position (anchor)
 *   //ardor struct list
 */
public final class StructCommands {

    private StructCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("ardor")
                    .then(ClientCommands.literal("struct")
                            .then(ClientCommands.literal("record")
                                    .then(ClientCommands.literal("start")
                                            .then(ClientCommands.argument("name", StringArgumentType.word())
                                                    .executes(ctx -> {
                                                        String name = StringArgumentType.getString(ctx, "name");
                                                        PlacementRecorder.start(name);
                                                        ctx.getSource().sendFeedback(Component.literal("[Ardor] recording struct '" + name + "'"));
                                                        return 1;
                                                    })))
                                    .then(ClientCommands.literal("stop")
                                            .executes(ctx -> {
                                                PlacementRecorder.stop();
                                                ctx.getSource().sendFeedback(Component.literal("[Ardor] struct saved"));
                                                return 1;
                                            })))
                            .then(ClientCommands.literal("import")
                                    .then(ClientCommands.argument("file", StringArgumentType.word())
                                            .then(ClientCommands.argument("name", StringArgumentType.word())
                                                    .executes(ctx -> {
                                                        importLitematic(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "file"),
                                                                StringArgumentType.getString(ctx, "name"));
                                                        return 1;
                                                    }))))
                            .then(ClientCommands.literal("build")
                                    .then(ClientCommands.argument("name", StringArgumentType.word())
                                            .executes(ctx -> {
                                                buildHere(ctx.getSource(), StringArgumentType.getString(ctx, "name"));
                                                return 1;
                                            })))
                            .then(ClientCommands.literal("list")
                                    .executes(ctx -> {
                                        var names = StructStore.list();
                                        ctx.getSource().sendFeedback(Component.literal(
                                                "[Ardor] structs: " + (names.isEmpty() ? "(none)" : String.join(", ", names))));
                                        return 1;
                                    }))));
        });
    }

    private static void importLitematic(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, String file, String name) {
        var path = FabricLoader.getInstance().getConfigDir().resolve("ardor-structs").resolve("import").resolve(file + ".litematic");
        try {
            StructFile struct = new StructFile();
            struct.name = name;
            struct.timeCreated = System.currentTimeMillis();
            struct.blocks = LitematicaImporter.importFile(path);
            struct.placements = BuildPlanner.plan(struct.blocks);
            struct.synthesizedPlacements = true;
            StructStore.save(struct);
            source.sendFeedback(Component.literal("[Ardor] imported '" + name + "' (" + struct.blocks.size() + " block(s)) from " + path));
        } catch (Exception e) {
            source.sendFeedback(Component.literal("[Ardor] import failed: " + e.getMessage()));
        }
    }

    private static void buildHere(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, String name) {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        try {
            StructFile struct = StructStore.load(name);
            StructBuilder.start(struct, player.blockPosition());
            source.sendFeedback(Component.literal("[Ardor] building '" + name + "'"));
        } catch (Exception e) {
            source.sendFeedback(Component.literal("[Ardor] build failed: " + e.getMessage()));
        }
    }
}
