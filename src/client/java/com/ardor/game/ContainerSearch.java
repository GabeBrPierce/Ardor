package com.ardor.game;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Nearby chest/barrel/shulker-box/etc. lookup -- nothing in this codebase
 * previously had a way to ask "is there a container nearby with item X in
 * it" (PathfindingController's shaft dump:/hole-seal code only ever WRITES
 * to a container at an already-known position). Added for
 * PathfindingController's tool-tier pre-flight check (2026-09-10, see
 * TODO.md) -- searching for a stashed pickaxe before deciding a new one
 * needs to be mined/smelted/crafted from scratch.
 *
 * Same cube-scan + getBlockEntity(pos) approach BridgeQueries.worldBlockRegion
 * and PathfindingController's own breakable-block scans already use. Checked
 * via `javap` whether ClientLevel exposes something cheaper/radius-indexed
 * instead: the only exposed block-entity set is
 * getGloballyRenderedBlockEntities(), which only covers specially far-
 * rendered block entities (beacons/conduits/etc.), not ordinary nearby
 * chests -- so a bounded brute-force scan is the real option here, same as
 * everywhere else in this codebase that needs "what's near this position."
 *
 * Widened from package-private to public (2026-09-10, container item cache/sources feature --
 * see TODO.md) so container.ContainerCache/ContainerFetchService (a different package) can reuse
 * asContainer/takeMatching instead of duplicating the same getBlockEntity(pos) instanceof Container
 * logic a second time.
 */
public final class ContainerSearch {

    private static final int MAX_SEARCH_RADIUS = 16;

    private ContainerSearch() {}

    /** Nearest container within radius containing at least one stack matching `match`, or null. */
    static BlockPos findNearbyContainerWithItem(Level level, BlockPos center, int radius, Predicate<ItemStack> match) {
        int r = Math.min(radius, MAX_SEARCH_RADIUS);
        BlockPos min = center.offset(-r, -r, -r);
        BlockPos max = center.offset(r, r, r);
        return BlockPos.betweenClosedStream(min, max)
                .filter(pos -> containerHasMatch(level, pos, match))
                .map(BlockPos::immutable)
                .min(Comparator.comparingDouble(pos -> pos.distSqr(center)))
                .orElse(null);
    }

    public static Container asContainer(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof Container c ? c : null;
    }

    /**
     * The real, network-synced contents of whatever container menu is CURRENTLY open, excluding
     * the player's own inventory/hotbar slots -- the ONLY way to see a physical container's actual
     * items client-side. Confirmed via javap against the real 26.1.2 client/common jars: neither
     * BaseContainerBlockEntity nor RandomizableContainerBlockEntity (chests' own base classes)
     * override getUpdateTag, so a chest's item list is never included in ordinary block-entity
     * sync (chunk load, block update) -- and the CLIENT-side menu factory a container's MenuType
     * calls when the open-screen packet arrives (e.g. ChestMenu.threeRows(int, Inventory), no
     * Container argument) builds its OWN throwaway Container for the synced items rather than
     * reusing the real BlockEntity at that position. So level.getBlockEntity(pos)'s own item list
     * is -- and always was -- empty for a chest the client hasn't currently got open, regardless of
     * how long a delay is added before reading it. See ContainerCache's own doc for how this gates
     * when PHYSICAL sources can actually be (re)cached.
     *
     * Works for any container menu type, not just chests: Slot.container is whatever real Container
     * object backs that specific slot, so filtering out slots backed by the player's own Inventory
     * leaves exactly the opened container's own slots, in order, regardless of which MenuType it is.
     */
    public static List<ItemStack> openMenuContents(AbstractContainerMenu menu, Container playerInventory) {
        List<ItemStack> out = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (slot.container == playerInventory) continue;
            out.add(slot.getItem());
        }
        return out;
    }

    private static boolean containerHasMatch(Level level, BlockPos pos, Predicate<ItemStack> match) {
        Container c = asContainer(level, pos);
        if (c == null) return false;
        for (int i = 0; i < c.getContainerSize(); i++) {
            if (match.test(c.getItem(i))) return true;
        }
        return false;
    }

    /** Removes and returns the first matching stack from the container at pos, or null if nothing matched (e.g. the container changed between the search and this call). Direct Container field writes, same simplification PathfindingController.depositInto already accepts for the write direction. */
    static ItemStack takeMatching(Level level, BlockPos pos, Predicate<ItemStack> match) {
        Container c = asContainer(level, pos);
        if (c == null) return null;
        return takeMatchingFrom(c, match);
    }

    /** Same removal logic as takeMatching, factored out so a caller that already has a Container in hand (the player's ender chest, a sub-container's resolved parent) doesn't need a BlockPos to use it. */
    public static ItemStack takeMatchingFrom(Container c, Predicate<ItemStack> match) {
        return takeMatchingFrom(c, match, Integer.MAX_VALUE);
    }

    /** Same as takeMatchingFrom, but never takes more than maxAmount from the matched slot -- container.FetchQueue's multi-source fulfillment loop uses this to pull only as much as is still needed from each source in turn, rather than always draining a whole stack. */
    public static ItemStack takeMatchingFrom(Container c, Predicate<ItemStack> match, int maxAmount) {
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack stack = c.getItem(i);
            if (match.test(stack)) {
                ItemStack taken = c.removeItem(i, Math.min(stack.getCount(), maxAmount));
                c.setChanged();
                return taken;
            }
        }
        return null;
    }
}
