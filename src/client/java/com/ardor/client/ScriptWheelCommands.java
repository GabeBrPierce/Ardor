package com.ardor.client;

import com.ardor.script.ScriptWheelEntry;
import com.ardor.script.ScriptWheelStore;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * WorldEdit-style //ardor wheel commands, configuring the "default" named wheel (ScriptWheelKey's
 * J-bound wheel; other named wheels are managed via WheelListScreen/WheelEditScreen instead, this
 * command set predates named wheels and stays pointed at "default" for backward compatibility):
 *
 *   //ardor wheel add <label> <script|macro> <name>
 *   //ardor wheel remove <label>
 *   //ardor wheel list
 */
public final class ScriptWheelCommands {

    private ScriptWheelCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("ardor")
                    .then(ClientCommands.literal("wheel")
                            .then(ClientCommands.literal("add")
                                    .then(ClientCommands.argument("label", StringArgumentType.string())
                                            .then(ClientCommands.argument("kind", StringArgumentType.word())
                                                    .then(ClientCommands.argument("name", StringArgumentType.word())
                                                            .executes(ctx -> {
                                                                String label = StringArgumentType.getString(ctx, "label");
                                                                String kind = StringArgumentType.getString(ctx, "kind");
                                                                String name = StringArgumentType.getString(ctx, "name");
                                                                if (!kind.equals("script") && !kind.equals("macro")) {
                                                                    ctx.getSource().sendFeedback(Component.literal("[Ardor] kind must be 'script' or 'macro'"));
                                                                    return 0;
                                                                }
                                                                List<ScriptWheelEntry> entries = ScriptWheelStore.load(ScriptWheelStore.DEFAULT_WHEEL);
                                                                entries.removeIf(e -> e.label.equals(label));
                                                                entries.add(new ScriptWheelEntry(label, kind, name));
                                                                ScriptWheelStore.save(ScriptWheelStore.DEFAULT_WHEEL, entries);
                                                                ctx.getSource().sendFeedback(Component.literal(
                                                                        "[Ardor] wheel wedge '" + label + "' -> " + kind + " '" + name + "'"));
                                                                return 1;
                                                            })))))
                            .then(ClientCommands.literal("remove")
                                    .then(ClientCommands.argument("label", StringArgumentType.string())
                                            .executes(ctx -> {
                                                String label = StringArgumentType.getString(ctx, "label");
                                                List<ScriptWheelEntry> entries = ScriptWheelStore.load(ScriptWheelStore.DEFAULT_WHEEL);
                                                boolean removed = entries.removeIf(e -> e.label.equals(label));
                                                ScriptWheelStore.save(ScriptWheelStore.DEFAULT_WHEEL, entries);
                                                ctx.getSource().sendFeedback(Component.literal(
                                                        removed ? "[Ardor] removed wedge '" + label + "'" : "[Ardor] no wedge named '" + label + "'"));
                                                return 1;
                                            })))
                            .then(ClientCommands.literal("list")
                                    .executes(ctx -> {
                                        List<ScriptWheelEntry> entries = ScriptWheelStore.load(ScriptWheelStore.DEFAULT_WHEEL);
                                        if (entries.isEmpty()) {
                                            ctx.getSource().sendFeedback(Component.literal("[Ardor] wheel: (no wedges configured)"));
                                            return 1;
                                        }
                                        StringBuilder sb = new StringBuilder("[Ardor] wheel: ");
                                        for (ScriptWheelEntry e : entries) {
                                            sb.append(e.label).append(" (").append(e.kind).append(" '").append(e.name).append("'), ");
                                        }
                                        ctx.getSource().sendFeedback(Component.literal(sb.substring(0, sb.length() - 2)));
                                        return 1;
                                    }))));
        });
    }
}
