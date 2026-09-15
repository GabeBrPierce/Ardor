package com.ardor.container;

import com.ardor.game.ContainerSearch;
import com.ardor.game.TickPoll;
import com.ardor.region.RegionManager;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashSet;
import java.util.Set;

/**
 * Auto-populates Item Sources the moment the player does the thing a source describes: opening a
 * real Container block registers PHYSICAL, a fillable cauldron registers CAULDRON, sending a
 * command registers COMMAND. Deduplicated against sources already in the current profile, and
 * position-bound cases skip recording where RegionManager flags ExcludeItemSources.
 */
public final class AutoSourceRecorder {

    private AutoSourceRecorder() {}

    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide()) {
                BlockPos pos = hitResult.getBlockPos();
                if (level.getBlockEntity(pos) instanceof Container) {
                    recordPosition(pos, SourceType.PHYSICAL);
                } else if (isFillableCauldron(level.getBlockState(pos).getBlock())) {
                    recordPosition(pos, SourceType.CAULDRON);
                }
            }
            return InteractionResult.PASS;
        });

        ClientSendMessageEvents.COMMAND.register(AutoSourceRecorder::recordCommand);
    }

    private static boolean isFillableCauldron(Block block) {
        return block == Blocks.WATER_CAULDRON || block == Blocks.LAVA_CAULDRON || block == Blocks.POWDER_SNOW_CAULDRON;
    }

    // CAULDRON reads block state, synced on chunk load -- a plain fixed delay is enough there.
    private static final int SCAN_DELAY_TICKS = 4;

    // PHYSICAL has no such shortcut: a chest-like BlockEntity's item list is only ever populated
    // client-side via its own currently-open menu, so this polls for player.containerMenu instead.
    private static final int MENU_OPEN_TIMEOUT_TICKS = 40; // 2s

    private static void recordPosition(BlockPos pos, SourceType type) {
        if (RegionManager.get().hasFlag(RegionManager.currentProfileKey(), pos, r -> r.excludeItemSources)) return;

        SourceManager manager = SourceManager.get();
        ContainerSource source = manager.currentProfile().sources.values().stream()
                .filter(s -> s.type == type && s.x != null
                        && s.x == pos.getX() && s.y == pos.getY() && s.z == pos.getZ())
                .findFirst().orElse(null);
        if (source == null) {
            source = new ContainerSource();
            source.type = type;
            source.x = pos.getX();
            source.y = pos.getY();
            source.z = pos.getZ();
            manager.add(source);
        }
        if (type == SourceType.PHYSICAL) {
            // A held right-click re-fires UseBlockCallback every tick when it never actually opens
            // a menu (e.g. crouching against a container) -- dedupe to at most one in-flight poll
            // per source, or repeated firings would pile up unbounded pollers.
            if (pollingSourceIds.add(source.id)) {
                pollForContainerMenu(source);
            }
        } else {
            ContainerSource toScan = source;
            TickPoll.after(SCAN_DELAY_TICKS, () -> {
                try {
                    ContainerCache.scan(toScan);
                } catch (RuntimeException e) {
                    System.err.println("[ardor] delayed container scan failed: " + e);
                }
            });
        }
    }

    /** Source ids with a container-open poll currently in flight -- see recordPosition's own doc for why this guard exists. Added when a poll starts, removed the instant it resolves. */
    private static final Set<String> pollingSourceIds = new HashSet<>();

    private static void pollForContainerMenu(ContainerSource source) {
        TickPoll.until(MENU_OPEN_TIMEOUT_TICKS,
                () -> {
                    var player = Minecraft.getInstance().player;
                    return player != null && player.containerMenu != player.inventoryMenu;
                },
                () -> {
                    pollingSourceIds.remove(source.id);
                    var player = Minecraft.getInstance().player;
                    ContainerCache.recordPhysicalContents(source.id,
                            ContainerSearch.openMenuContents(player.containerMenu, player.getInventory()));
                },
                () -> pollingSourceIds.remove(source.id)); // gave up -- container never actually opened, or the player disconnected
    }

    private static void recordCommand(String command) {
        SourceManager manager = SourceManager.get();
        boolean known = manager.currentProfile().sources.values().stream()
                .anyMatch(s -> s.type == SourceType.COMMAND && command.equals(s.command));
        if (known) return;

        ContainerSource source = new ContainerSource();
        source.type = SourceType.COMMAND;
        source.command = command;
        manager.add(source);
    }
}
