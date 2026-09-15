package com.ardor.container;

import com.ardor.game.ContainerSearch;
import com.ardor.game.PathfindingController;
import com.ardor.game.RotationUtil;
import com.ardor.game.TickPoll;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Fetches a specific cached item from its source: walk to a physical source (or its
 * sub-container's physical parent) and take the matching stack, take directly from the player's
 * own ender chest (no walking), or send the declared command for a COMMAND source (no
 * verification -- see TODO.md). PHYSICAL fetch opens the real container (same poll-for-menu
 * pattern as RealCraftingController) and takes via a real QUICK_MOVE container-input packet.
 */
public final class ContainerFetchService {

    private ContainerFetchService() {}

    public static void fetch(ContainerSource source, CachedItem item, Consumer<ItemStack> onFetched, Consumer<String> onFailed) {
        switch (source.type) {
            case PHYSICAL -> fetchPhysical(source, item, onFetched, onFailed);
            case ENDER_CHEST -> fetchEnderChest(item, onFetched, onFailed);
            case SUBCONTAINER -> fetchSubContainer(source, item, onFetched, onFailed);
            case COMMAND -> fetchCommand(source, onFetched, onFailed);
            case CAULDRON -> fetchCauldron(source, onFetched, onFailed);
        }
    }

    private static void fetchPhysical(ContainerSource source, CachedItem item, Consumer<ItemStack> onFetched, Consumer<String> onFailed) {
        if (source.x == null) {
            onFailed.accept("source has no position");
            return;
        }
        BlockPos pos = new BlockPos(source.x, source.y, source.z);
        PathfindingController.walkThenRun(pos, "couldn't reach " + source.displayLabel(SourceManager.get()),
                () -> openPhysicalAndTake(source, pos, item,
                        taken -> onFetched.accept(displayStack(item.itemId, taken)),
                        onFailed),
                onFailed);
    }

    private static ItemStack displayStack(String itemId, int count) {
        Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(itemId)).orElse(Items.BARRIER);
        return new ItemStack(item, Math.max(1, count));
    }

    // ---- Real container open + click, PHYSICAL sources only -------------------------------------
    // See this file's class doc for why: a chest-like BlockEntity is never populated with real
    // items client-side except through its own currently-open menu.

    private static final int MENU_OPEN_TIMEOUT_TICKS = 40; // 2s -- same budget AutoSourceRecorder's own container-open poll uses

    /**
     * Opens the real container at pos, polls for the menu, then takes the first matching slot via
     * one real QUICK_MOVE (shift-click) packet -- vanilla's own quickMoveStack decides placement
     * (merges into an existing stack first, an empty slot otherwise). Reports however much moved.
     *
     * KNOWN LIMITATION: an exact partial amount from a slot holding more than currently wanted
     * isn't possible with a single click -- QUICK_MOVE always takes the whole slot. Can only
     * overshoot, never undershoot -- see fetchPhysicalPartial's own doc.
     */
    private static void openPhysicalAndTake(ContainerSource source, BlockPos pos, CachedItem item, IntConsumer onTaken, Consumer<String> onFailed) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null) {
            onFailed.accept("no client level/player loaded");
            return;
        }
        if (ContainerSearch.asContainer(level, pos) == null) {
            onFailed.accept("container at " + pos + " is gone or the chunk isn't loaded");
            return;
        }
        RotationUtil.lookAtExact(player, pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        pollForOpenMenuThenTake(source, item, onTaken, onFailed);
    }

    private static void pollForOpenMenuThenTake(ContainerSource source, CachedItem item, IntConsumer onTaken, Consumer<String> onFailed) {
        TickPoll.until(MENU_OPEN_TIMEOUT_TICKS,
                () -> {
                    var player = Minecraft.getInstance().player;
                    return player != null && player.containerMenu != player.inventoryMenu;
                },
                () -> takeFromOpenMenu(Minecraft.getInstance().player.containerMenu, source, item, onTaken, onFailed),
                () -> onFailed.accept("container at " + source.displayLabel(SourceManager.get()) + " never opened (timed out)"));
    }

    /** menu is confirmed open at this point -- find the matching slot (excluding the player's own inventory/hotbar slots, via Slot.container) and take it with one real click. */
    private static void takeFromOpenMenu(AbstractContainerMenu menu, ContainerSource source, CachedItem item, IntConsumer onTaken, Consumer<String> onFailed) {
        LocalPlayer player = Minecraft.getInstance().player;
        Container playerInv = player.getInventory();
        Slot match = null;
        for (Slot slot : menu.slots) {
            if (slot.container == playerInv) continue;
            if (!slot.getItem().isEmpty() && matches(slot.getItem(), item)) {
                match = slot;
                break;
            }
        }
        if (match == null) {
            player.closeContainer();
            onFailed.accept("'" + item.displayName + "' is no longer there (changed since the last scan)");
            return;
        }
        int available = match.getItem().getCount();
        Minecraft.getInstance().gameMode.handleContainerInput(menu.containerId, match.index, 0, ContainerInput.QUICK_MOVE, player);
        // Real container clicks mutate Slots synchronously client-side too (confirmed via javap by
        // RealCraftingController's own doc), so the same Slot instance already reflects the result.
        int taken = available - (match.getItem().isEmpty() ? 0 : match.getItem().getCount());
        // Cache stays honest with what's actually left, while the menu is conveniently still open.
        ContainerCache.recordPhysicalContents(source.id, ContainerSearch.openMenuContents(menu, playerInv));
        player.closeContainer();
        if (taken <= 0) {
            onFailed.accept("'" + item.displayName + "' couldn't be moved -- inventory full?");
            return;
        }
        onTaken.accept(taken);
    }

    private static void fetchEnderChest(CachedItem item, Consumer<ItemStack> onFetched, Consumer<String> onFailed) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            onFailed.accept("no client player loaded");
            return;
        }
        ItemStack taken = ContainerSearch.takeMatchingFrom(player.getEnderChestInventory(), stack -> matches(stack, item));
        giveOrFail(taken, item, onFetched, onFailed);
    }

    private static void fetchSubContainer(ContainerSource source, CachedItem item, Consumer<ItemStack> onFetched, Consumer<String> onFailed) {
        BlockPos parentPos = resolveParentPos(source);
        if (parentPos == null) {
            onFailed.accept("sub-container's parent has no fixed position to walk to");
            return;
        }
        PathfindingController.walkThenRun(parentPos, "couldn't reach " + source.displayLabel(SourceManager.get()), () -> {
            ItemStack host = ContainerCache.resolveSubContainerHost(source);
            if (host == null) {
                onFailed.accept("sub-container's parent slot is empty or unreadable");
                return;
            }
            ItemStack taken = SubContainerAccess.takeMatching(host, item.itemId);
            if (taken == null) {
                onFailed.accept("that item is no longer in the sub-container");
                return;
            }
            writeHostBack(source, host);
            giveOrFail(taken, item, onFetched, onFailed);
        }, onFailed);
    }

    private static void writeHostBack(ContainerSource source, ItemStack mutatedHost) {
        Level level = Minecraft.getInstance().level;
        BlockPos parentPos = resolveParentPos(source);
        if (level == null || parentPos == null) return;
        Container parentContainer = ContainerSearch.asContainer(level, parentPos);
        if (parentContainer == null) return;
        parentContainer.setItem(source.parentSlot, mutatedHost);
        parentContainer.setChanged();
    }

    private static BlockPos resolveParentPos(ContainerSource source) {
        if (source.parentSourceId != null) {
            ContainerSource parent = SourceManager.get().get(source.parentSourceId);
            if (parent == null || parent.x == null) return null;
            return new BlockPos(parent.x, parent.y, parent.z);
        }
        if (source.parentX != null) return new BlockPos(source.parentX, source.parentY, source.parentZ);
        return null;
    }

    /** No verification -- see TODO.md: the user's own spec is explicit that a command source's Expected Contents is hand-typed metadata, not auto-detected, so "fetching" one just sends it. Gated by CommandCooldowns -- see that class doc -- so a source still on its user-entered cooldown fails fast instead of resending a command the server would just reject. */
    private static void fetchCommand(ContainerSource source, Consumer<ItemStack> onFetched, Consumer<String> onFailed) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            onFailed.accept("no client player loaded");
            return;
        }
        if (source.command == null || source.command.isBlank()) {
            onFailed.accept("source has no command set");
            return;
        }
        if (CommandCooldowns.isOnCooldown(source.id)) {
            onFailed.accept("on cooldown for another " + CommandCooldowns.remainingSeconds(source.id) + "s");
            return;
        }
        String command = source.command.startsWith("/") ? source.command.substring(1) : source.command;
        player.connection.sendCommand(command);
        CommandCooldowns.markUsed(source.id, source.cooldownSeconds);
        onFetched.accept(ItemStack.EMPTY);
    }

    /** Walks to the cauldron and fills an empty bucket from it (CauldronAccess -- direct block-state + ItemStack mutation, no real interaction packet, matching this class's existing container-take simplifications). Fails clearly if the cauldron isn't full any more or the player has no empty bucket, rather than the user's "either show a cauldron full of X or a source block" fallback silently doing nothing. */
    private static void fetchCauldron(ContainerSource source, Consumer<ItemStack> onFetched, Consumer<String> onFailed) {
        if (source.x == null) {
            onFailed.accept("source has no position");
            return;
        }
        BlockPos pos = new BlockPos(source.x, source.y, source.z);
        PathfindingController.walkThenRun(pos, "couldn't reach " + source.displayLabel(SourceManager.get()), () -> {
            Level level = Minecraft.getInstance().level;
            LocalPlayer player = Minecraft.getInstance().player;
            if (level == null || player == null) {
                onFailed.accept("no client level/player loaded");
                return;
            }
            ItemStack filled = CauldronAccess.fillBucket(level, pos, player.getInventory());
            if (filled == null) {
                onFailed.accept(CauldronAccess.liquidAt(level, pos) == null
                        ? "cauldron at " + pos + " isn't full any more (changed since the last scan)"
                        : "no empty bucket available");
                return;
            }
            player.getInventory().add(filled);
            onFetched.accept(filled);
        }, onFailed);
    }

    private static void giveOrFail(ItemStack taken, CachedItem item, Consumer<ItemStack> onFetched, Consumer<String> onFailed) {
        if (taken == null || taken.isEmpty()) {
            onFailed.accept("'" + item.displayName + "' is no longer there (changed since the last scan)");
            return;
        }
        Minecraft.getInstance().player.getInventory().add(taken);
        onFetched.accept(taken);
    }

    private static boolean matches(ItemStack stack, CachedItem item) {
        if (stack.isEmpty()) return false;
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && id.toString().equals(item.itemId);
    }

    // ---- Slot-targeted partial fetch: container.FetchQueue's multi-source fulfillment loop -----
    // Unlike fetch() above (which always takes a whole matched stack and lets vanilla's own
    // Inventory.add() decide where it lands), these take up to maxAmount and land it in a specific
    // inventory slot -- the "specified slot" mechanic the Fetch Items screen's click-to-target flow
    // needs, and the piece that lets one logical request draw from several sources in turn while
    // the player watches the same slot's count climb.

    public static void fetchPartialInto(ContainerSource source, CachedItem item, int maxAmount, int targetSlot, IntConsumer onTaken, Consumer<String> onFailed) {
        switch (source.type) {
            case PHYSICAL -> fetchPhysicalPartial(source, item, maxAmount, targetSlot, onTaken, onFailed);
            case ENDER_CHEST -> fetchEnderChestPartial(item, maxAmount, targetSlot, onTaken, onFailed);
            case SUBCONTAINER -> fetchSubContainerPartial(source, item, maxAmount, targetSlot, onTaken, onFailed);
            case COMMAND -> fetchCommand(source, taken -> onTaken.accept(maxAmount), onFailed); // a command is fire-once, not partial -- see fetchCommand's own doc
            case CAULDRON -> fetchCauldronPartial(source, targetSlot, onTaken, onFailed); // one bucket at a time, not partial either -- see fetchCauldron's own doc
        }
    }

    private static void fetchCauldronPartial(ContainerSource source, int targetSlot, IntConsumer onTaken, Consumer<String> onFailed) {
        if (source.x == null) {
            onFailed.accept("source has no position");
            return;
        }
        BlockPos pos = new BlockPos(source.x, source.y, source.z);
        PathfindingController.walkThenRun(pos, "couldn't reach " + source.displayLabel(SourceManager.get()), () -> {
            Level level = Minecraft.getInstance().level;
            LocalPlayer player = Minecraft.getInstance().player;
            if (level == null || player == null) {
                onFailed.accept("no client level/player loaded");
                return;
            }
            ItemStack filled = CauldronAccess.fillBucket(level, pos, player.getInventory());
            if (filled == null) {
                onFailed.accept(CauldronAccess.liquidAt(level, pos) == null
                        ? "cauldron at " + pos + " isn't full any more (changed since the last scan)"
                        : "no empty bucket available");
                return;
            }
            Inventory inv = player.getInventory();
            ItemStack existing = inv.getItem(targetSlot);
            if (existing.isEmpty()) {
                inv.setItem(targetSlot, filled);
            } else if (ItemStack.isSameItemSameComponents(existing, filled)) {
                existing.grow(filled.getCount());
            } else {
                inv.add(filled);
            }
            onTaken.accept(filled.getCount());
        }, onFailed);
    }

    /**
     * maxAmount/targetSlot are deliberately unused here, unlike the ENDER_CHEST/SUBCONTAINER
     * siblings below -- those manipulate an ItemStack purely in memory and have to be told exactly
     * where it lands, but a PHYSICAL take is a real QUICK_MOVE click (see openPhysicalAndTake's own
     * doc): vanilla itself decides placement, and in practice merges into the SAME target slot's
     * already-partially-filled stack from an earlier source in this fetch, since quickMoveStack
     * prefers merging into an existing stack of the same item before falling back to an empty slot.
     */
    private static void fetchPhysicalPartial(ContainerSource source, CachedItem item, int maxAmount, int targetSlot, IntConsumer onTaken, Consumer<String> onFailed) {
        if (source.x == null) {
            onFailed.accept("source has no position");
            return;
        }
        BlockPos pos = new BlockPos(source.x, source.y, source.z);
        PathfindingController.walkThenRun(pos, "couldn't reach " + source.displayLabel(SourceManager.get()),
                () -> openPhysicalAndTake(source, pos, item, onTaken, onFailed),
                onFailed);
    }

    private static void fetchEnderChestPartial(CachedItem item, int maxAmount, int targetSlot, IntConsumer onTaken, Consumer<String> onFailed) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            onFailed.accept("no client player loaded");
            return;
        }
        ItemStack taken = ContainerSearch.takeMatchingFrom(player.getEnderChestInventory(), stack -> matches(stack, item), maxAmount);
        giveIntoSlot(taken, item, targetSlot, onTaken, onFailed);
    }

    private static void fetchSubContainerPartial(ContainerSource source, CachedItem item, int maxAmount, int targetSlot, IntConsumer onTaken, Consumer<String> onFailed) {
        BlockPos parentPos = resolveParentPos(source);
        if (parentPos == null) {
            onFailed.accept("sub-container's parent has no fixed position to walk to");
            return;
        }
        PathfindingController.walkThenRun(parentPos, "couldn't reach " + source.displayLabel(SourceManager.get()), () -> {
            ItemStack host = ContainerCache.resolveSubContainerHost(source);
            if (host == null) {
                onFailed.accept("sub-container's parent slot is empty or unreadable");
                return;
            }
            ItemStack taken = SubContainerAccess.takeMatching(host, item.itemId);
            if (taken == null) {
                onFailed.accept("that item is no longer in the sub-container");
                return;
            }
            ItemStack toGive = taken;
            if (taken.getCount() > maxAmount) {
                toGive = taken.split(maxAmount);
                SubContainerAccess.putBack(host, taken); // put the excess back -- takeMatching always removes the whole matched stack
            }
            writeHostBack(source, host);
            giveIntoSlot(toGive, item, targetSlot, onTaken, onFailed);
        }, onFailed);
    }

    /** Lands taken into targetSlot specifically -- merges into whatever's already there if it's the same item+components, otherwise (slot empty, or something else landed there since the click) sets/falls back to a normal give so nothing taken is ever lost. */
    private static void giveIntoSlot(ItemStack taken, CachedItem item, int targetSlot, IntConsumer onTaken, Consumer<String> onFailed) {
        if (taken == null || taken.isEmpty()) {
            onFailed.accept("'" + item.displayName + "' is no longer there (changed since the last scan)");
            return;
        }
        Inventory inv = Minecraft.getInstance().player.getInventory();
        ItemStack existing = inv.getItem(targetSlot);
        if (existing.isEmpty()) {
            inv.setItem(targetSlot, taken);
        } else if (ItemStack.isSameItemSameComponents(existing, taken)) {
            existing.grow(taken.getCount());
        } else {
            inv.add(taken); // target slot no longer available for this item -- give it normally rather than losing it
        }
        onTaken.accept(taken.getCount());
    }
}
