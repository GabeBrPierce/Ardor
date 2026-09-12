package com.ardor.container;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Name-then-component filtering shared by the Fetch Items screen and the bridge's
 * container.cache.list query: an item whose display name matches the query ranks above one that
 * only matches via a component summary line (custom name, enchantments, ...), per the spec's
 * "first by name, then by item component."
 *
 * Radius only ever constrains sources that HAVE a position (PHYSICAL, and SUBCONTAINER via its
 * parent) -- the ender chest and command sources aren't position-bound at all, so they're always
 * eligible regardless of the radius field, same as they'd be with "All" checked.
 */
public final class CacheSearch {

    private CacheSearch() {}

    public record Result(ContainerSource source, CachedItem item, boolean nameMatch) {}

    /**
     * One aggregated grid slot: every real Result across every matching source that shares the
     * same (itemId, componentSummary) -- e.g. three chests each holding a plain diamond sword
     * collapse into one Group with totalCount 3, while a diamond sword with an enchantment, a
     * custom name, or different durability (see game.ComponentSummarizer) is a different
     * componentSummary and therefore its own Group. fromCommand groups are synthesized from a
     * COMMAND source's declared Expected Contents (ContainerSource.desiredContents, kind=ITEM) --
     * see groupedSearch's class doc below for why they're never merged with real physical stock.
     */
    public record Group(String itemId, String displayName, List<String> componentSummary, int totalCount, boolean fromCommand, List<Result> members) {}

    /**
     * groupedSearch layers two things on top of search(): (1) collapses same-item-same-component
     * Results from possibly many different sources into one Group with a summed count, so the
     * Fetch Items grid shows one slot per distinct item rather than one per physical chest slot;
     * (2) synthesizes one Group per COMMAND source's declared kind=ITEM Expected Contents entry
     * (today ContainerCache never populates a live cache for COMMAND sources at all -- see its own
     * class doc -- so without this, command-granted items could never appear/be fetched from this
     * grid). Command-synthesized groups are kept structurally separate (fromCommand=true, never
     * merged into a real-stock Group even when the itemId+components happen to match) so a single
     * fetch's fulfillment loop never has to mix "take from N chests" with "send a command" in one
     * pass -- see container.FetchQueue.
     *
     * search(...) itself is left untouched -- the bridge's container.cache.list query already
     * depends on its existing per-slot (ungrouped) shape.
     */
    public static List<Group> groupedSearch(String query, boolean enabledOnly, boolean all, int radius, BlockPos center) {
        List<Result> real = search(query, enabledOnly, all, radius, center);
        List<Result> commandResults = commandDeclaredResults(query, enabledOnly);

        Map<String, List<Result>> byKey = new LinkedHashMap<>();
        for (Result r : real) byKey.computeIfAbsent(groupKey(r.item()), k -> new ArrayList<>()).add(r);

        List<Group> groups = new ArrayList<>();
        for (List<Result> members : byKey.values()) {
            CachedItem first = members.get(0).item();
            int total = members.stream().mapToInt(m -> m.item().count).sum();
            groups.add(new Group(first.itemId, first.displayName, first.componentSummary, total, false, members));
        }
        for (Result r : commandResults) {
            groups.add(new Group(r.item().itemId, r.item().displayName, r.item().componentSummary, r.item().count, true, List.of(r)));
        }
        return groups;
    }

    private static String groupKey(CachedItem item) {
        return item.itemId + "|" + String.join("|", item.componentSummary);
    }

    /** One synthetic Result per COMMAND source's declared kind=ITEM ContentsEntry, filtered by the same query rules search() applies to real items -- see groupedSearch's class doc. */
    private static List<Result> commandDeclaredResults(String query, boolean enabledOnly) {
        String q = query == null ? "" : query.trim().toLowerCase();
        List<Result> out = new ArrayList<>();
        for (ContainerSource source : SourceManager.get().currentProfile().sources.values()) {
            if (source.type != SourceType.COMMAND) continue;
            if (enabledOnly && !source.enabled) continue;
            for (ContentsEntry entry : source.desiredContents) {
                if (entry.kind != ContentsEntry.Kind.ITEM || entry.itemId == null) continue;
                CachedItem synthetic = toSyntheticCommandItem(source, entry);
                boolean nameMatch = q.isEmpty() || synthetic.displayName.toLowerCase().contains(q) || synthetic.itemId.toLowerCase().contains(q);
                boolean componentMatch = !nameMatch && synthetic.componentSummary.stream().anyMatch(line -> line.toLowerCase().contains(q));
                if (nameMatch || componentMatch) out.add(new Result(source, synthetic, nameMatch));
            }
        }
        return out;
    }

    private static CachedItem toSyntheticCommandItem(ContainerSource source, ContentsEntry entry) {
        var item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(entry.itemId)).orElse(Items.BARRIER);
        String displayName = new ItemStack(item).getHoverName().getString();
        int count = Math.max(1, entry.delta);
        List<String> summary = entry.customNameMatch != null ? List.of("named contains \"" + entry.customNameMatch + "\"") : List.of();
        return new CachedItem(source.id, -1, entry.itemId, count, displayName, summary);
    }

    public static List<Result> search(String query, boolean enabledOnly, boolean all, int radius, BlockPos center) {
        String q = query == null ? "" : query.trim().toLowerCase();
        List<Result> nameMatches = new ArrayList<>();
        List<Result> componentMatches = new ArrayList<>();

        for (ContainerSource source : SourceManager.get().currentProfile().sources.values()) {
            if (enabledOnly && !source.enabled) continue;
            if (!all && !withinRadius(source, radius, center)) continue;

            for (CachedItem item : ContainerCache.itemsFor(source.id)) {
                if (q.isEmpty()) {
                    nameMatches.add(new Result(source, item, true));
                    continue;
                }
                if (item.displayName.toLowerCase().contains(q) || item.itemId.toLowerCase().contains(q)) {
                    nameMatches.add(new Result(source, item, true));
                } else if (item.componentSummary.stream().anyMatch(line -> line.toLowerCase().contains(q))) {
                    componentMatches.add(new Result(source, item, false));
                }
            }
        }

        List<Result> results = new ArrayList<>(nameMatches);
        results.addAll(componentMatches);
        return results;
    }

    private static boolean withinRadius(ContainerSource source, int radius, BlockPos center) {
        BlockPos pos = positionOf(source);
        if (pos == null) return true; // not position-bound (ender chest, command) -- always eligible
        return pos.distSqr(center) <= (long) radius * radius;
    }

    /** Package-visible (not private) so container.FetchQueue can reuse the exact same "where is this source, if anywhere" logic to sort a group's members by walking distance, instead of duplicating it. */
    static BlockPos positionOf(ContainerSource source) {
        if ((source.type == SourceType.PHYSICAL || source.type == SourceType.CAULDRON) && source.x != null) {
            return new BlockPos(source.x, source.y, source.z);
        }
        if (source.type == SourceType.SUBCONTAINER) {
            if (source.parentSourceId != null) {
                ContainerSource parent = SourceManager.get().get(source.parentSourceId);
                if (parent != null && parent.x != null) return new BlockPos(parent.x, parent.y, parent.z);
                return null;
            }
            if (source.parentX != null) return new BlockPos(source.parentX, source.parentY, source.parentZ);
        }
        return null;
    }
}
