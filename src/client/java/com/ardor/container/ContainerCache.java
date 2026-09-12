package com.ardor.container;

import com.ardor.game.ComponentSummarizer;
import com.ardor.game.ContainerSearch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Last-known contents per source, with a timestamp -- see TODO.md's "Scanning / caching
 * mechanic". In-memory only, deliberately NOT persisted alongside SourceManager's own JSON: a
 * cached stack list is only ever "last known," inherently stale the moment the client restarts
 * (world state can change while the mod isn't running), so writing it to disk would just be
 * writing down a guess with a misleadingly durable timestamp. Rescanned on Fetch Items screen
 * open plus an explicit Refresh button -- the simplest of the trigger options the spec offered,
 * and genuinely cheap here since every PHYSICAL/SUBCONTAINER scan is a single known-position
 * getBlockEntity lookup, not a radius search (unlike ContainerSearch's own nearby-container scan).
 *
 * PHYSICAL sources whose chunk isn't loaded (Level.isLoaded, confirmed real via javap) are marked
 * stale rather than scanned -- same self-healing convention BlockIndex uses elsewhere in this
 * project: the entry just keeps its last successful scan until the chunk loads again and a scan
 * succeeds. COMMAND sources have no live cache at all, by design -- see TODO.md.
 */
public final class ContainerCache {

    private static final Map<String, List<CachedItem>> ITEMS = new LinkedHashMap<>();
    private static final Map<String, Long> SCANNED_AT = new LinkedHashMap<>();
    private static final Map<String, Boolean> STALE = new LinkedHashMap<>();

    private ContainerCache() {}

    public static List<CachedItem> itemsFor(String sourceId) {
        return ITEMS.getOrDefault(sourceId, List.of());
    }

    public static Long scannedAt(String sourceId) {
        return SCANNED_AT.get(sourceId);
    }

    public static boolean isStale(String sourceId) {
        return STALE.getOrDefault(sourceId, false);
    }

    public static void scanAll() {
        for (ContainerSource source : SourceManager.get().currentProfile().sources.values()) {
            if (source.enabled) scan(source);
        }
    }

    public static void scan(ContainerSource source) {
        switch (source.type) {
            case COMMAND -> { /* purely declarative, no live cache -- see TODO.md */ }
            case ENDER_CHEST -> scanEnderChest(source);
            case PHYSICAL -> scanPhysical(source);
            case SUBCONTAINER -> scanSubContainer(source);
            case CAULDRON -> scanCauldron(source);
        }
    }

    private static void scanEnderChest(ContainerSource source) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        writeFromContainer(source.id, player.getEnderChestInventory());
    }

    private static void scanPhysical(ContainerSource source) {
        Level level = Minecraft.getInstance().level;
        if (level == null || source.x == null) return;
        BlockPos pos = new BlockPos(source.x, source.y, source.z);
        if (!level.isLoaded(pos)) {
            STALE.put(source.id, true);
            return;
        }
        Container c = ContainerSearch.asContainer(level, pos);
        if (c == null) {
            STALE.put(source.id, true);
            return;
        }
        writeFromContainer(source.id, c);
    }

    /** A full cauldron always scans as one CachedItem for its corresponding bucket (count 1), regardless of whether the player currently holds an empty bucket to actually fill -- that check happens at fetch time (ContainerFetchService), not here, per the user's own "either show a cauldron full of lava or a lava source block [when there's no bucket]" spec. An empty/not-full cauldron scans as no items at all, same shape a real empty container would. */
    private static void scanCauldron(ContainerSource source) {
        Level level = Minecraft.getInstance().level;
        if (level == null || source.x == null) return;
        BlockPos pos = new BlockPos(source.x, source.y, source.z);
        if (!level.isLoaded(pos)) {
            STALE.put(source.id, true);
            return;
        }
        CauldronAccess.Liquid liquid = CauldronAccess.liquidAt(level, pos);
        if (liquid == null) {
            ITEMS.put(source.id, List.of());
        } else {
            String bucketId = CauldronAccess.bucketItemId(liquid);
            var item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(bucketId)).orElse(null);
            String displayName = item != null ? new ItemStack(item).getHoverName().getString() : bucketId;
            ITEMS.put(source.id, List.of(new CachedItem(source.id, 0, bucketId, 1, displayName, List.of())));
        }
        SCANNED_AT.put(source.id, System.currentTimeMillis());
        STALE.put(source.id, false);
    }

    private static void scanSubContainer(ContainerSource source) {
        ItemStack host = resolveSubContainerHost(source);
        if (host == null) {
            STALE.put(source.id, true);
            return;
        }
        List<ItemStack> contents = SubContainerAccess.contentsOf(host);
        List<CachedItem> items = new ArrayList<>();
        for (int i = 0; i < contents.size(); i++) {
            items.add(toCachedItem(source.id, i, contents.get(i)));
        }
        ITEMS.put(source.id, items);
        SCANNED_AT.put(source.id, System.currentTimeMillis());
        STALE.put(source.id, false);
    }

    /**
     * Resolves a sub-container's host ItemStack (the shulker box / bundle itself) from its parent
     * -- either a named parent source's slot ("parent name" mode) or a raw parent position's slot
     * ("parent location" mode). Null (caller marks stale) if the parent isn't currently readable:
     * chunk unloaded, parent source missing/not physical, or the slot is empty.
     */
    static ItemStack resolveSubContainerHost(ContainerSource source) {
        Level level = Minecraft.getInstance().level;
        if (level == null || source.parentSlot == null) return null;

        BlockPos parentPos;
        if (source.parentSourceId != null) {
            ContainerSource parent = SourceManager.get().get(source.parentSourceId);
            if (parent == null || parent.type != SourceType.PHYSICAL || parent.x == null) return null;
            parentPos = new BlockPos(parent.x, parent.y, parent.z);
        } else if (source.parentX != null) {
            parentPos = new BlockPos(source.parentX, source.parentY, source.parentZ);
        } else {
            return null;
        }
        if (!level.isLoaded(parentPos)) return null;

        Container parentContainer = ContainerSearch.asContainer(level, parentPos);
        if (parentContainer == null || source.parentSlot < 0 || source.parentSlot >= parentContainer.getContainerSize()) return null;
        ItemStack host = parentContainer.getItem(source.parentSlot);
        return host.isEmpty() ? null : host;
    }

    private static void writeFromContainer(String sourceId, Container c) {
        List<CachedItem> items = new ArrayList<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack stack = c.getItem(i);
            if (stack.isEmpty()) continue;
            items.add(toCachedItem(sourceId, i, stack));
        }
        ITEMS.put(sourceId, items);
        SCANNED_AT.put(sourceId, System.currentTimeMillis());
        STALE.put(sourceId, false);
    }

    private static CachedItem toCachedItem(String sourceId, int slot, ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return new CachedItem(sourceId, slot, id != null ? id.toString() : "unknown",
                stack.getCount(), stack.getHoverName().getString(), ComponentSummarizer.summarize(stack));
    }
}
