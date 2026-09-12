package com.ardor.container;

import com.ardor.region.RegionManager;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Auto-populates Item Sources the moment the player actually does the thing a source describes,
 * instead of requiring a manual Add New first: right-clicking a real Container block registers a
 * PHYSICAL source at that block's position (ender chests excluded -- EnderChestBlockEntity does
 * NOT implement Container, confirmed via javap against the real 26.1.2 client jar, so this can't
 * double up with the built-in ENDER_CHEST pseudo-source SourceManager already auto-registers),
 * right-clicking a fillable cauldron (CauldronAccess -- vanilla cauldrons have no BlockEntity/
 * Container at all, so this is a separate block-id check, not an extension of the Container one)
 * registers a CAULDRON source the same way, and sending any command registers a COMMAND source
 * with that exact text. All are deduplicated against sources already in the current profile so
 * repeat opens/reruns don't pile up duplicate rows. The two position-bound cases additionally skip
 * auto-recording entirely when RegionManager.hasFlag reports ExcludeItemSources for that position
 * -- commands aren't position-bound, so that flag doesn't apply to command auto-recording.
 *
 * UseBlockCallback fires from both the client-side and (in singleplayer) the integrated-server-
 * side interaction mixins sharing this one static event -- filtered to level.isClientSide() only,
 * since this is a client-only mod (ClientModInitializer) and ContainerCache.scan ultimately reads
 * Minecraft.getInstance().level, which should only ever be touched from the client thread's own
 * callback, not the integrated server thread's.
 *
 * ClientSendMessageEvents.COMMAND hands back the command text with no leading slash (Fabric's own
 * documented convention, matching ALLOW_COMMAND/MODIFY_COMMAND's naming) -- same shape
 * ContainerSource.command/ContainerFetchService's sendCommand already expect.
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

    private static void recordPosition(BlockPos pos, SourceType type) {
        if (RegionManager.get().hasFlag(RegionManager.currentProfileKey(), pos, r -> r.excludeItemSources)) return;

        SourceManager manager = SourceManager.get();
        boolean known = manager.currentProfile().sources.values().stream().anyMatch(s ->
                s.type == type && s.x != null
                        && s.x == pos.getX() && s.y == pos.getY() && s.z == pos.getZ());
        if (known) return;

        ContainerSource source = new ContainerSource();
        source.type = type;
        source.x = pos.getX();
        source.y = pos.getY();
        source.z = pos.getZ();
        manager.add(source);
        ContainerCache.scan(source);
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
