package com.ardor.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ardor.container.CacheSearch;
import com.ardor.container.CachedItem;
import com.ardor.container.ContainerCache;
import com.ardor.container.ContainerFetchService;
import com.ardor.container.ContainerSource;
import com.ardor.container.ContentsEntry;
import com.ardor.container.SourceManager;
import com.ardor.container.SourceType;
import com.ardor.container.SubKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * container.* bridge surface (see TODO.md's "Bridge protocol" spec): listing/adding/editing/
 * removing/enabling sources, listing cached items (name-then-component filter, optional radius,
 * optional "all"), and triggering a fetch of a specific cached item. Aggregates SourceManager/
 * ContainerCache/ContainerFetchService into JSON the same way BridgeUIController aggregates its
 * own feature's manager classes -- field names are spelled out (no abbreviations) since this is
 * the exact surface the companion web UI's container tab will be built against, per the spec.
 *
 * source.add/edit/remove/setEnabled are COMMANDS (fire-and-forget, no return value), matching
 * region.set/region.delete's own precedent -- a caller re-fetches container.sources.list to see
 * the result rather than getting it back inline. fetch is likewise a command (walk+take can take
 * a while); container.fetch.status is the runner.status-style query for polling its outcome,
 * since there's no server-push channel in this bridge to report it proactively.
 */
final class BridgeContainerController {

    private BridgeContainerController() {}

    // ------------------------------------------------------------------ queries

    static JsonObject sourcesList() {
        JsonArray sources = new JsonArray();
        for (ContainerSource s : SourceManager.get().currentProfile().sources.values()) {
            sources.add(sourceToJson(s));
        }
        JsonObject result = new JsonObject();
        result.add("sources", sources);
        return result;
    }

    /** {query?, all?, radius?, enabledOnly?} -- see container.CacheSearch for the name-then-component filter and radius semantics. */
    static JsonObject cacheList(JsonObject msg) {
        String query = msg.has("query") ? msg.get("query").getAsString() : "";
        boolean all = !msg.has("all") || msg.get("all").getAsBoolean();
        boolean enabledOnly = !msg.has("enabledOnly") || msg.get("enabledOnly").getAsBoolean();
        int radius = msg.has("radius") ? msg.get("radius").getAsInt() : 16;
        BlockPos center = playerPos();

        JsonArray items = new JsonArray();
        for (CacheSearch.Result r : CacheSearch.search(query, enabledOnly, all, radius, center)) {
            JsonObject o = cachedItemToJson(r.item());
            o.addProperty("sourceName", r.source().displayLabel(SourceManager.get()));
            o.addProperty("sourceType", typeName(r.source().type));
            o.addProperty("matchedBy", r.nameMatch() ? "name" : "component");
            items.add(o);
        }
        JsonObject result = new JsonObject();
        result.add("items", items);
        return result;
    }

    private static FetchStatus lastFetch = new FetchStatus("idle", null, null, null);

    private record FetchStatus(String state, String sourceId, String itemId, String reason) {}

    static JsonObject fetchStatus() {
        JsonObject result = new JsonObject();
        result.addProperty("state", lastFetch.state());
        if (lastFetch.sourceId() != null) result.addProperty("sourceId", lastFetch.sourceId());
        if (lastFetch.itemId() != null) result.addProperty("itemId", lastFetch.itemId());
        if (lastFetch.reason() != null) result.addProperty("reason", lastFetch.reason());
        return result;
    }

    // ------------------------------------------------------------------ commands

    static void sourceAdd(JsonObject msg) {
        ContainerSource s = new ContainerSource();
        applyFields(s, msg);
        SourceManager.get().add(s);
    }

    static void sourceEdit(JsonObject msg) {
        String id = msg.get("id").getAsString();
        ContainerSource s = SourceManager.get().get(id);
        if (s == null) throw new IllegalArgumentException("no such source: " + id);
        applyFields(s, msg);
        SourceManager.get().update(s);
    }

    static void sourceRemove(JsonObject msg) {
        SourceManager.get().remove(msg.get("id").getAsString());
    }

    static void sourceSetEnabled(JsonObject msg) {
        SourceManager.get().setEnabled(msg.get("id").getAsString(), msg.get("enabled").getAsBoolean());
    }

    /** No sourceId given rescans every enabled source in the current profile; a given sourceId rescans just that one. */
    static void cacheRefresh(JsonObject msg) {
        if (msg.has("sourceId")) {
            ContainerSource s = SourceManager.get().get(msg.get("sourceId").getAsString());
            if (s == null) throw new IllegalArgumentException("no such source: " + msg.get("sourceId").getAsString());
            ContainerCache.scan(s);
        } else {
            ContainerCache.scanAll();
        }
    }

    /** {sourceId, slot} -- slot identifies which cached item within the source (see CachedItem.slot), re-resolved against the current cache rather than trusting a stale snapshot. */
    static void fetch(JsonObject msg) {
        String sourceId = msg.get("sourceId").getAsString();
        int slot = msg.get("slot").getAsInt();
        ContainerSource source = SourceManager.get().get(sourceId);
        if (source == null) throw new IllegalArgumentException("no such source: " + sourceId);
        CachedItem item = ContainerCache.itemsFor(sourceId).stream().filter(i -> i.slot == slot).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no cached item at slot " + slot + " for source " + sourceId + " -- refresh the cache first"));

        lastFetch = new FetchStatus("pending", sourceId, item.itemId, null);
        ContainerFetchService.fetch(source, item,
                taken -> lastFetch = new FetchStatus("success", sourceId, item.itemId, null),
                reason -> lastFetch = new FetchStatus("failed", sourceId, item.itemId, reason));
    }

    // ------------------------------------------------------------------ JSON <-> model

    private static void applyFields(ContainerSource s, JsonObject msg) {
        if (msg.has("name")) s.name = msg.get("name").getAsString();
        if (msg.has("type")) s.type = parseType(msg.get("type").getAsString());
        if (msg.has("enabled")) s.enabled = msg.get("enabled").getAsBoolean();
        if (msg.has("x")) s.x = msg.get("x").getAsInt();
        if (msg.has("y")) s.y = msg.get("y").getAsInt();
        if (msg.has("z")) s.z = msg.get("z").getAsInt();
        if (msg.has("subKind")) s.subKind = parseSubKind(msg.get("subKind").getAsString());
        if (msg.has("parentSourceId")) s.parentSourceId = msg.get("parentSourceId").getAsString();
        if (msg.has("parentSlot")) s.parentSlot = msg.get("parentSlot").getAsInt();
        if (msg.has("parentX")) s.parentX = msg.get("parentX").getAsInt();
        if (msg.has("parentY")) s.parentY = msg.get("parentY").getAsInt();
        if (msg.has("parentZ")) s.parentZ = msg.get("parentZ").getAsInt();
        if (msg.has("command")) s.command = msg.get("command").getAsString();
        if (msg.has("desiredContents")) s.desiredContents = parseContents(msg.getAsJsonArray("desiredContents"));
    }

    private static List<ContentsEntry> parseContents(JsonArray arr) {
        List<ContentsEntry> list = new ArrayList<>();
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            ContentsEntry e = new ContentsEntry();
            e.kind = "category".equals(o.get("kind").getAsString()) ? ContentsEntry.Kind.CATEGORY : ContentsEntry.Kind.ITEM;
            if (o.has("category")) e.category = o.get("category").getAsString();
            if (o.has("itemId")) e.itemId = o.get("itemId").getAsString();
            if (o.has("customNameMatch")) e.customNameMatch = o.get("customNameMatch").getAsString();
            if (o.has("delta")) e.delta = o.get("delta").getAsInt();
            list.add(e);
        }
        return list;
    }

    private static JsonObject sourceToJson(ContainerSource s) {
        JsonObject o = new JsonObject();
        o.addProperty("id", s.id);
        if (s.name != null) o.addProperty("name", s.name);
        o.addProperty("type", typeName(s.type));
        o.addProperty("enabled", s.enabled);
        o.addProperty("displayLabel", s.displayLabel(SourceManager.get()));
        if (s.x != null) { o.addProperty("x", s.x); o.addProperty("y", s.y); o.addProperty("z", s.z); }
        if (s.subKind != null) o.addProperty("subKind", s.subKind == SubKind.SHULKER ? "shulkerBox" : "bundle");
        if (s.parentSourceId != null) o.addProperty("parentSourceId", s.parentSourceId);
        if (s.parentX != null) { o.addProperty("parentX", s.parentX); o.addProperty("parentY", s.parentY); o.addProperty("parentZ", s.parentZ); }
        if (s.parentSlot != null) o.addProperty("parentSlot", s.parentSlot);
        if (s.command != null) o.addProperty("command", s.command);
        JsonArray contents = new JsonArray();
        for (ContentsEntry e : s.desiredContents) contents.add(contentsEntryToJson(e));
        o.add("desiredContents", contents);
        return o;
    }

    private static JsonObject contentsEntryToJson(ContentsEntry e) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", e.kind == ContentsEntry.Kind.CATEGORY ? "category" : "item");
        if (e.category != null) o.addProperty("category", e.category);
        if (e.itemId != null) o.addProperty("itemId", e.itemId);
        if (e.customNameMatch != null) o.addProperty("customNameMatch", e.customNameMatch);
        if (e.delta != 0) o.addProperty("delta", e.delta);
        return o;
    }

    private static JsonObject cachedItemToJson(CachedItem c) {
        JsonObject o = new JsonObject();
        o.addProperty("sourceId", c.sourceId);
        o.addProperty("slot", c.slot);
        o.addProperty("itemId", c.itemId);
        o.addProperty("count", c.count);
        o.addProperty("displayName", c.displayName);
        JsonArray summary = new JsonArray();
        c.componentSummary.forEach(summary::add);
        o.add("componentSummary", summary);
        return o;
    }

    private static String typeName(SourceType type) {
        return switch (type) {
            case PHYSICAL -> "physical";
            case SUBCONTAINER -> "subcontainer";
            case ENDER_CHEST -> "enderChest";
            case COMMAND -> "command";
            case CAULDRON -> "cauldron";
        };
    }

    private static SourceType parseType(String s) {
        return switch (s) {
            case "physical" -> SourceType.PHYSICAL;
            case "subcontainer" -> SourceType.SUBCONTAINER;
            case "enderChest" -> SourceType.ENDER_CHEST;
            case "command" -> SourceType.COMMAND;
            case "cauldron" -> SourceType.CAULDRON;
            default -> throw new IllegalArgumentException("unknown source type: " + s);
        };
    }

    private static SubKind parseSubKind(String s) {
        return "bundle".equals(s) ? SubKind.BUNDLE : SubKind.SHULKER;
    }

    private static BlockPos playerPos() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null ? player.blockPosition() : BlockPos.ZERO;
    }
}
