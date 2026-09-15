package com.ardor.container;

import com.ardor.game.ComponentSummarizer;
import com.ardor.game.ContainerSearch;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Last-known contents per source, with a timestamp -- see TODO.md's "Scanning / caching
 * mechanic". Persisted to disk (config/ardor-container-cache.json, same Gson/FabricLoader
 * config-dir convention as SourceManager's own JSON) so the Fetch Items list has something to
 * show immediately on next launch instead of starting empty until the first scan.
 *
 * PHYSICAL sources are the one real exception to "rescanned on demand": "opened chests, added as
 * physical containers, but didn't log the items inside" -- confirmed real, via javap against the
 * actual 26.1.2 jars (see ContainerSearch.openMenuContents's own doc for the full finding). A
 * chest-like BlockEntity's item list is NEVER populated client-side outside of that exact chest's
 * own currently-open menu -- ordinary block-entity sync (chunk load, block update) never includes
 * it, and the client-side menu factory builds its own separate throwaway Container for the synced
 * items rather than writing back into the real BlockEntity. So scanPhysical (below) can only ever
 * correctly read a PHYSICAL source's contents at the exact moment it's freshly opened --
 * AutoSourceRecorder does that, via ContainerSearch.openMenuContents + recordPhysicalContents.
 * A later scanAll()/Refresh call CANNOT get anything real for a chest that isn't open right that
 * instant, so scanPhysical no longer even tries to re-read the (always-empty) BlockEntity -- doing
 * so used to silently CLOBBER good cached data from the last real open with a wrong empty read, on
 * every Fetch Items screen open/Refresh. It still updates staleness (whether the chunk is loaded),
 * since that's genuinely readable without opening anything.
 *
 * ENDER_CHEST and SUBCONTAINER don't have this problem: the player's own ender chest inventory is
 * always fully synced to them regardless of open state (it's their own inventory-adjacent data,
 * not a remote block), and a sub-container's contents live in the holding ItemStack's own data
 * components, synced as part of whatever visible inventory/container already holds that stack --
 * neither needs an open menu to be readable. CAULDRON reads block STATE (fluid type), which real
 * chunk sync does include, so it's unaffected too. COMMAND sources have no live cache at all, by
 * design -- see TODO.md.
 */
public final class ContainerCache {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Map<String, List<CachedItem>> ITEMS = new LinkedHashMap<>();
    private static final Map<String, Long> SCANNED_AT = new LinkedHashMap<>();
    private static final Map<String, Boolean> STALE = new LinkedHashMap<>();

    static {
        load();
    }

    private ContainerCache() {}

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("ardor-container-cache.json");
    }

    private static final class CacheData {
        Map<String, List<CachedItem>> items = new LinkedHashMap<>();
        Map<String, Long> scannedAt = new LinkedHashMap<>();
    }

    private static void load() {
        Path p = path();
        if (!Files.exists(p)) return;
        try {
            CacheData data = GSON.fromJson(Files.readString(p), CacheData.class);
            if (data != null) {
                if (data.items != null) ITEMS.putAll(data.items);
                if (data.scannedAt != null) SCANNED_AT.putAll(data.scannedAt);
            }
        } catch (IOException e) {
            System.err.println("[ardor] container cache: failed to load " + p + ": " + e);
        }
    }

    private static void save() {
        CacheData data = new CacheData();
        data.items = ITEMS;
        data.scannedAt = SCANNED_AT;
        try {
            Files.writeString(path(), GSON.toJson(data));
        } catch (IOException e) {
            System.err.println("[ardor] container cache: failed to save: " + e);
        }
    }

    public static List<CachedItem> itemsFor(String sourceId) {
        return ITEMS.getOrDefault(sourceId, List.of());
    }

    public static Long scannedAt(String sourceId) {
        return SCANNED_AT.get(sourceId);
    }

    public static boolean isStale(String sourceId) {
        return STALE.getOrDefault(sourceId, false);
    }

    /** Scans every enabled source but saves once at the end, instead of once per source. */
    public static void scanAll() {
        for (ContainerSource source : SourceManager.get().currentProfile().sources.values()) {
            if (source.enabled) scan(source, false);
        }
        save();
    }

    public static void scan(ContainerSource source) {
        scan(source, true);
    }

    private static void scan(ContainerSource source, boolean saveImmediately) {
        switch (source.type) {
            case COMMAND -> { /* purely declarative, no live cache -- see TODO.md */ }
            case ENDER_CHEST -> scanEnderChest(source, saveImmediately);
            case PHYSICAL -> scanPhysical(source);
            case SUBCONTAINER -> scanSubContainer(source, saveImmediately);
            case CAULDRON -> scanCauldron(source, saveImmediately);
        }
    }

    private static void scanEnderChest(ContainerSource source, boolean saveImmediately) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        writeFromContainer(source.id, player.getEnderChestInventory(), saveImmediately);
    }

    /** Only updates staleness now -- see this class's own doc for why re-reading the BlockEntity here used to silently clobber good cached data with a wrong empty read. Real contents only ever come from recordPhysicalContents, at the moment AutoSourceRecorder catches the container actually open. */
    private static void scanPhysical(ContainerSource source) {
        Level level = Minecraft.getInstance().level;
        if (level == null || source.x == null) return;
        BlockPos pos = new BlockPos(source.x, source.y, source.z);
        if (!level.isLoaded(pos)) {
            STALE.put(source.id, true);
            return;
        }
        STALE.put(source.id, ContainerSearch.asContainer(level, pos) == null);
    }

    /** AutoSourceRecorder calls this with ContainerSearch.openMenuContents' result at the moment a PHYSICAL source's container is confirmed actually open -- the only point real contents are obtainable. */
    public static void recordPhysicalContents(String sourceId, List<ItemStack> stacks) {
        List<CachedItem> items = new ArrayList<>();
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (stack.isEmpty()) continue;
            items.add(toCachedItem(sourceId, i, stack));
        }
        ITEMS.put(sourceId, items);
        SCANNED_AT.put(sourceId, System.currentTimeMillis());
        STALE.put(sourceId, false);
        save();
    }

    /** A full cauldron always scans as one CachedItem for its corresponding bucket (count 1), regardless of whether the player currently holds an empty bucket to actually fill -- that check happens at fetch time (ContainerFetchService), not here, per the user's own "either show a cauldron full of lava or a lava source block [when there's no bucket]" spec. An empty/not-full cauldron scans as no items at all, same shape a real empty container would. */
    private static void scanCauldron(ContainerSource source, boolean saveImmediately) {
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
        if (saveImmediately) save();
    }

    private static void scanSubContainer(ContainerSource source, boolean saveImmediately) {
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
        if (saveImmediately) save();
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

    private static void writeFromContainer(String sourceId, Container c, boolean saveImmediately) {
        List<CachedItem> items = new ArrayList<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack stack = c.getItem(i);
            if (stack.isEmpty()) continue;
            items.add(toCachedItem(sourceId, i, stack));
        }
        ITEMS.put(sourceId, items);
        SCANNED_AT.put(sourceId, System.currentTimeMillis());
        STALE.put(sourceId, false);
        if (saveImmediately) save();
    }

    private static CachedItem toCachedItem(String sourceId, int slot, ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return new CachedItem(sourceId, slot, id != null ? id.toString() : "unknown",
                stack.getCount(), stack.getHoverName().getString(), ComponentSummarizer.summarize(stack));
    }
}
