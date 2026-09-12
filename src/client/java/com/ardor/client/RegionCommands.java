package com.ardor.client;

import com.ardor.region.RegionManager;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * WorldEdit-style "//ardor" client commands (registering the literal "/ardor" --
 * Minecraft's own chat-command trigger strips one leading "/", so typing
 * "//ardor ..." in chat leaves "/ardor ..." for brigadier to parse, same trick
 * WorldEdit uses for its "//" commands):
 *
 *   //ardor set region <name> <x1> <y1> <z1> <x2> <y2> <z2>  -- create/move a region
 *   //ardor edit region <name>                                -- open RegionEditScreen
 *
 * Regions are created/edited in the CURRENT profile (RegionManager.currentProfileKey()
 * -- "singleplayer", the connected server's address, or "global"). Built with
 * intermediate variables rather than one deeply-chained expression -- a first
 * attempt at the same tree as a single nested `.then(...)` chain had a
 * paren-count bug that the compiler caught but a human skimming it might not.
 */
public final class RegionCommands {

    private RegionCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            RequiredArgumentBuilder<FabricClientCommandSource, Integer> z2 =
                    ClientCommands.argument("z2", IntegerArgumentType.integer()).executes(RegionCommands::executeSetRegion);
            RequiredArgumentBuilder<FabricClientCommandSource, Integer> y2 =
                    ClientCommands.argument("y2", IntegerArgumentType.integer()).then(z2);
            RequiredArgumentBuilder<FabricClientCommandSource, Integer> x2 =
                    ClientCommands.argument("x2", IntegerArgumentType.integer()).then(y2);
            RequiredArgumentBuilder<FabricClientCommandSource, Integer> z1 =
                    ClientCommands.argument("z1", IntegerArgumentType.integer()).then(x2);
            RequiredArgumentBuilder<FabricClientCommandSource, Integer> y1 =
                    ClientCommands.argument("y1", IntegerArgumentType.integer()).then(z1);
            RequiredArgumentBuilder<FabricClientCommandSource, Integer> x1 =
                    ClientCommands.argument("x1", IntegerArgumentType.integer()).then(y1);
            RequiredArgumentBuilder<FabricClientCommandSource, String> setRegionName =
                    ClientCommands.argument("name", StringArgumentType.word()).then(x1);
            LiteralArgumentBuilder<FabricClientCommandSource> setRegion =
                    ClientCommands.literal("region").then(setRegionName);
            LiteralArgumentBuilder<FabricClientCommandSource> set =
                    ClientCommands.literal("set").then(setRegion);

            RequiredArgumentBuilder<FabricClientCommandSource, String> editRegionName =
                    ClientCommands.argument("name", StringArgumentType.word()).executes(RegionCommands::executeEditRegion);
            LiteralArgumentBuilder<FabricClientCommandSource> editRegion =
                    ClientCommands.literal("region").then(editRegionName);
            LiteralArgumentBuilder<FabricClientCommandSource> edit =
                    ClientCommands.literal("edit").then(editRegion);

            dispatcher.register(ClientCommands.literal("ardor").then(set).then(edit));
        });
    }

    private static int executeSetRegion(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        BlockPos a = new BlockPos(
                IntegerArgumentType.getInteger(ctx, "x1"),
                IntegerArgumentType.getInteger(ctx, "y1"),
                IntegerArgumentType.getInteger(ctx, "z1"));
        BlockPos b = new BlockPos(
                IntegerArgumentType.getInteger(ctx, "x2"),
                IntegerArgumentType.getInteger(ctx, "y2"),
                IntegerArgumentType.getInteger(ctx, "z2"));
        try {
            RegionManager.get().setRegion(RegionManager.currentProfileKey(), name, a, b);
            ctx.getSource().sendFeedback(Component.literal(
                    "[Ardor] region '" + name + "' set to " + a.toShortString() + " -> " + b.toShortString()));
        } catch (RuntimeException e) {
            ctx.getSource().sendError(Component.literal("[Ardor] " + e.getMessage()));
        }
        return 1;
    }

    private static int executeEditRegion(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        ctx.getSource().getClient().execute(() -> ctx.getSource().getClient().setScreen(new RegionEditScreen(name)));
        return 1;
    }
}
