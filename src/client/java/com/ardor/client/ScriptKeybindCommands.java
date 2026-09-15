package com.ardor.client;

import com.ardor.script.ScriptKeybindStore;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.network.chat.Component;

import java.util.Map;

/**
 * WorldEdit-style //ardor keybind commands -- maps one of the fixed ScriptKeybinds slots (bind the
 * physical key itself via vanilla Controls, under "Ardor Script Slot N") to a saved script:
 *
 *   //ardor keybind set <slot> <scriptName>
 *   //ardor keybind clear <slot>
 *   //ardor keybind list
 */
public final class ScriptKeybindCommands {

    private ScriptKeybindCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("ardor")
                    .then(ClientCommands.literal("keybind")
                            .then(ClientCommands.literal("set")
                                    .then(ClientCommands.argument("slot", IntegerArgumentType.integer(1, ScriptKeybinds.SLOT_COUNT))
                                            .then(ClientCommands.argument("scriptName", StringArgumentType.word())
                                                    .executes(ctx -> {
                                                        int slot = IntegerArgumentType.getInteger(ctx, "slot");
                                                        String scriptName = StringArgumentType.getString(ctx, "scriptName");
                                                        Map<Integer, String> map = ScriptKeybindStore.load();
                                                        map.put(slot, scriptName);
                                                        ScriptKeybindStore.save(map);
                                                        ctx.getSource().sendFeedback(Component.literal(
                                                                "[Ardor] script slot " + slot + " -> '" + scriptName + "' (bind the physical key via Controls -> Ardor Script Slot " + slot + ")"));
                                                        return 1;
                                                    }))))
                            .then(ClientCommands.literal("clear")
                                    .then(ClientCommands.argument("slot", IntegerArgumentType.integer(1, ScriptKeybinds.SLOT_COUNT))
                                            .executes(ctx -> {
                                                int slot = IntegerArgumentType.getInteger(ctx, "slot");
                                                Map<Integer, String> map = ScriptKeybindStore.load();
                                                map.remove(slot);
                                                ScriptKeybindStore.save(map);
                                                ctx.getSource().sendFeedback(Component.literal("[Ardor] cleared script slot " + slot));
                                                return 1;
                                            })))
                            .then(ClientCommands.literal("list")
                                    .executes(ctx -> {
                                        Map<Integer, String> map = ScriptKeybindStore.load();
                                        if (map.isEmpty()) {
                                            ctx.getSource().sendFeedback(Component.literal("[Ardor] script keybinds: (none set)"));
                                            return 1;
                                        }
                                        StringBuilder sb = new StringBuilder("[Ardor] script keybinds: ");
                                        map.forEach((slot, name) -> sb.append("slot ").append(slot).append("='").append(name).append("', "));
                                        ctx.getSource().sendFeedback(Component.literal(sb.substring(0, sb.length() - 2)));
                                        return 1;
                                    }))));
        });
    }
}
