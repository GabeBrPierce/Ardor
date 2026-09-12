package com.ardor.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.ardor.game.BlockIndex;
import com.ardor.game.BlockRefs;
import com.ardor.game.GameObjectSearch;
import com.ardor.region.Region;
import com.ardor.region.RegionManager;
import com.ardor.region.RegionProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Phase 1 read-only bridge queries (see the approved plan): inventory.contents,
 * world.time, world.blockAt, world.nearestBlocks (BlockIndex-backed),
 * world.nearbyEntities, world.surroundings, world.chunkHeightmap,
 * session.info. Mostly generalized ports of AgentOps'
 * getpos/getsurroundings/getchunk (which this supersedes -- see TODO.md,
 * AgentControlChannel retirement is Phase 6) plus QueryController's `time`,
 * now addressed by the bridge's request/response protocol instead of a
 * bespoke file-poll or ascii-verb path.
 */
final class BridgeQueries {

    private BridgeQueries() {}

    private static final int DEFAULT_RADIUS = 8;
    private static final int MAX_RADIUS = 48;

    static JsonObject inventoryContents() {
        Inventory inv = requirePlayer().getInventory();
        JsonArray slots = new JsonArray();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            JsonObject slot = new JsonObject();
            slot.addProperty("slot", i);
            slot.addProperty("id", idOf(BuiltInRegistries.ITEM.getKey(stack.getItem())));
            slot.addProperty("count", stack.getCount());
            slots.add(slot);
        }
        JsonObject result = new JsonObject();
        result.addProperty("size", inv.getContainerSize());
        result.addProperty("selectedSlot", inv.getSelectedSlot());
        result.add("slots", slots);
        return result;
    }

    static JsonObject worldTime() {
        Level level = requireLevel();
        long time = level.getOverworldClockTime() % 24000;
        String phase = time < 12000 ? "day" : time < 13000 ? "dusk" : time < 23000 ? "night" : "dawn";
        JsonObject result = new JsonObject();
        result.addProperty("ticks", time);
        result.addProperty("phase", phase);
        return result;
    }

    static JsonObject worldBlockAt(JsonObject msg) {
        Level level = requireLevel();
        BlockPos pos = readPos(msg);
        return blockInfo(level, pos);
    }

    private static final int MAX_REGION_BLOCKS = 4096; // 16x16x16 -- a full-chunk-ish cache fill, bounded so a bad request can't ask for the whole world

    /** {min:{x,y,z}, max:{x,y,z}} -- bulk block info for a cuboid, so a companion-side terrain cache can be filled with one round trip instead of one query per position (see the approved plan's Phase 2 terrain cache). Bounded to MAX_REGION_BLOCKS. */
    static JsonObject worldBlockRegion(JsonObject msg) {
        Level level = requireLevel();
        BlockPos min = readPos(msg.getAsJsonObject("min"));
        BlockPos max = readPos(msg.getAsJsonObject("max"));
        long volume = (long) (Math.abs(max.getX() - min.getX()) + 1)
                * (Math.abs(max.getY() - min.getY()) + 1)
                * (Math.abs(max.getZ() - min.getZ()) + 1);
        if (volume > MAX_REGION_BLOCKS) {
            throw new IllegalArgumentException("world.blockRegion: " + volume + " blocks requested, max is " + MAX_REGION_BLOCKS);
        }

        JsonArray blocks = new JsonArray();
        BlockPos.betweenClosedStream(min, max).forEach(pos -> {
            JsonObject info = blockInfo(level, pos.immutable());
            info.addProperty("x", pos.getX());
            info.addProperty("y", pos.getY());
            info.addProperty("z", pos.getZ());
            blocks.add(info);
        });
        JsonObject result = new JsonObject();
        result.add("blocks", blocks);
        return result;
    }

    /**
     * The full per-block picture BlockWorldMovement's isPassable/isStandable/isBreakable/isHazard
     * checks need -- id, air, collisionEmpty (physically passable), water (swimmable), hazard (fire/
     * soul_fire/cactus/magma_block/powder_snow OR a non-water fluid like lava -- BlockWorldMovement's
     * own isPassable() blocks both the named hazard blocks AND any fluid that isn't water the exact
     * same way, so both are folded into this one flag rather than needing a third boolean the
     * companion would have to know to check). Computed mod-side so a companion-side terrain model
     * never needs its own copy of the hazard block list or collision-shape logic -- it just reads
     * booleans.
     */
    private static JsonObject blockInfo(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        var fluid = level.getFluidState(pos);
        boolean water = fluid.typeHolder().is(FluidTags.WATER);
        boolean hazard = block == Blocks.FIRE || block == Blocks.SOUL_FIRE || block == Blocks.CACTUS
                || block == Blocks.MAGMA_BLOCK || block == Blocks.POWDER_SNOW
                || (!fluid.isEmpty() && !water);
        boolean collisionEmpty = state.getCollisionShape(level, pos).isEmpty();

        JsonObject result = new JsonObject();
        result.addProperty("id", idOf(BuiltInRegistries.BLOCK.getKey(block)));
        result.addProperty("air", state.isAir());
        result.addProperty("collisionEmpty", collisionEmpty);
        result.addProperty("water", water);
        result.addProperty("hazard", hazard);
        return result;
    }

    /** {ids:[...] | tag:"#logs" | id:"oak_log", center?:{x,y,z}, radius?, limit?} -- ids/tag/id are alternatives, first one present wins. */
    static JsonObject worldNearestBlocks(JsonObject msg) {
        Level level = requireLevel();
        Set<Block> targets = resolveTargets(msg);
        BlockPos center = msg.has("center") ? readPos(msg.getAsJsonObject("center")) : requirePlayer().blockPosition();
        int radius = Math.min(msg.has("radius") ? msg.get("radius").getAsInt() : DEFAULT_RADIUS * 2, MAX_RADIUS);
        int limit = msg.has("limit") ? msg.get("limit").getAsInt() : 20;

        List<BlockPos> matches = BlockIndex.nearest(targets, center, radius, level, limit);
        JsonArray array = new JsonArray();
        for (BlockPos p : matches) {
            JsonObject pos = new JsonObject();
            pos.addProperty("x", p.getX());
            pos.addProperty("y", p.getY());
            pos.addProperty("z", p.getZ());
            pos.addProperty("distance", Math.sqrt(p.distSqr(center)));
            array.add(pos);
        }
        JsonObject result = new JsonObject();
        result.add("matches", array);
        return result;
    }

    /** {text, center?:{x,y,z}, radius?, limit?} -- substring search over block and entity ids near a point (see GameObjectSearch, shared with QueryController's ascii-grammar `query search <text>`). */
    static JsonObject worldSearch(JsonObject msg) {
        Level level = requireLevel();
        String text = msg.get("text").getAsString();
        BlockPos center = msg.has("center") ? readPos(msg.getAsJsonObject("center")) : requirePlayer().blockPosition();
        int radius = Math.min(msg.has("radius") ? msg.get("radius").getAsInt() : DEFAULT_RADIUS * 2, MAX_RADIUS);
        int limit = msg.has("limit") ? msg.get("limit").getAsInt() : 20;

        JsonArray array = new JsonArray();
        for (GameObjectSearch.Match m : GameObjectSearch.search(text, center, level, radius, limit)) {
            JsonObject o = new JsonObject();
            o.addProperty("kind", m.kind());
            o.addProperty("id", m.id());
            o.addProperty("x", m.pos().getX());
            o.addProperty("y", m.pos().getY());
            o.addProperty("z", m.pos().getZ());
            if (m.entityId() != null) o.addProperty("entityId", m.entityId());
            o.addProperty("distance", m.distance());
            array.add(o);
        }
        JsonObject result = new JsonObject();
        result.add("matches", array);
        return result;
    }

    private static Set<Block> resolveTargets(JsonObject msg) {
        if (msg.has("tag")) return BlockRefs.resolve(msg.get("tag").getAsString());
        if (msg.has("id")) return BlockRefs.resolve(msg.get("id").getAsString());
        if (msg.has("ids")) {
            Set<Block> result = new java.util.HashSet<>();
            for (var el : msg.getAsJsonArray("ids")) result.addAll(BlockRefs.resolve(el.getAsString()));
            return result;
        }
        throw new IllegalArgumentException("world.nearestBlocks needs one of id/tag/ids");
    }

    static JsonObject worldNearbyEntities(JsonObject msg) {
        LocalPlayer player = requirePlayer();
        Level level = player.level();
        int radius = Math.min(msg.has("radius") ? msg.get("radius").getAsInt() : DEFAULT_RADIUS, MAX_RADIUS);

        List<Entity> entities = level.getEntitiesOfClass(Entity.class,
                player.getBoundingBox().inflate(radius), e -> e != player);
        JsonArray array = new JsonArray();
        for (Entity e : entities) {
            JsonObject eo = new JsonObject();
            eo.addProperty("entityId", e.getId());
            eo.addProperty("type", idOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())));
            eo.addProperty("x", e.getX());
            eo.addProperty("y", e.getY());
            eo.addProperty("z", e.getZ());
            eo.addProperty("distance", Math.sqrt(e.distanceToSqr(player)));
            array.add(eo);
        }
        JsonObject result = new JsonObject();
        result.add("entities", array);
        return result;
    }

    /** {radius?} -- dropped ItemEntitys near the player, with actual item id/count (unlike worldNearbyEntities, which only reports entity type "minecraft:item" with no contents). */
    static JsonObject droppedItems(JsonObject msg) {
        LocalPlayer player = requirePlayer();
        Level level = player.level();
        int radius = Math.min(msg.has("radius") ? msg.get("radius").getAsInt() : DEFAULT_RADIUS, MAX_RADIUS);

        List<ItemEntity> entities = level.getEntitiesOfClass(ItemEntity.class,
                player.getBoundingBox().inflate(radius));
        JsonArray array = new JsonArray();
        for (ItemEntity e : entities) {
            ItemStack stack = e.getItem();
            JsonObject eo = new JsonObject();
            eo.addProperty("entityId", e.getId());
            eo.addProperty("id", idOf(BuiltInRegistries.ITEM.getKey(stack.getItem())));
            eo.addProperty("count", stack.getCount());
            eo.addProperty("x", e.getX());
            eo.addProperty("y", e.getY());
            eo.addProperty("z", e.getZ());
            eo.addProperty("distance", Math.sqrt(e.distanceToSqr(player)));
            array.add(eo);
        }
        JsonObject result = new JsonObject();
        result.add("items", array);
        return result;
    }

    /** Block-id -> count within a cube, same summarized shape as AgentOps.getSurroundings. */
    static JsonObject worldSurroundings(JsonObject msg) {
        LocalPlayer player = requirePlayer();
        Level level = player.level();
        int radius = Math.min(msg.has("radius") ? msg.get("radius").getAsInt() : DEFAULT_RADIUS, MAX_RADIUS);
        BlockPos center = player.blockPosition();
        BlockPos min = center.offset(-radius, -radius, -radius);
        BlockPos max = center.offset(radius, radius, radius);

        TreeMap<String, Integer> counts = new TreeMap<>();
        BlockPos.betweenClosedStream(min, max).forEach(pos -> {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) return;
            counts.merge(idOf(BuiltInRegistries.BLOCK.getKey(state.getBlock())), 1, Integer::sum);
        });
        JsonObject blocks = new JsonObject();
        counts.forEach(blocks::addProperty);

        JsonObject result = new JsonObject();
        result.addProperty("center", center.toShortString());
        result.addProperty("radius", radius);
        result.add("blockCounts", blocks);
        return result;
    }

    /**
     * {radius?} -- nearby chest/barrel/shulker-box/etc. block entities and their contents. Added
     * alongside PathfindingController's tool-tier pre-flight check (2026-09-10, see TODO.md) --
     * "search nearby containers for iron tools" needed a way to see container contents at all,
     * which nothing in this file previously exposed (BridgeQueries had no container-reading query;
     * the mod-side game logic itself uses its own scan, game.ContainerSearch, not this -- this is
     * purely for a companion/observer to see the same thing). Same cube-scan + getBlockEntity(pos)
     * approach worldBlockRegion above already uses; empty containers are skipped since they're not
     * useful for the "is there a tool/material stashed nearby" use case this exists for.
     */
    static JsonObject worldNearbyContainers(JsonObject msg) {
        LocalPlayer player = requirePlayer();
        Level level = player.level();
        int radius = Math.min(msg.has("radius") ? msg.get("radius").getAsInt() : DEFAULT_RADIUS, MAX_RADIUS);
        BlockPos center = player.blockPosition();
        BlockPos min = center.offset(-radius, -radius, -radius);
        BlockPos max = center.offset(radius, radius, radius);

        JsonArray containers = new JsonArray();
        BlockPos.betweenClosedStream(min, max).forEach(pos -> {
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof Container container)) return;
            JsonArray items = new JsonArray();
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty()) continue;
                JsonObject item = new JsonObject();
                item.addProperty("id", idOf(BuiltInRegistries.ITEM.getKey(stack.getItem())));
                item.addProperty("count", stack.getCount());
                items.add(item);
            }
            if (items.isEmpty()) return;
            JsonObject c = new JsonObject();
            c.addProperty("x", pos.getX());
            c.addProperty("y", pos.getY());
            c.addProperty("z", pos.getZ());
            c.addProperty("distance", Math.sqrt(pos.distSqr(center)));
            c.add("items", items);
            containers.add(c);
        });
        JsonObject result = new JsonObject();
        result.add("containers", containers);
        return result;
    }

    /** Surface heightmap for a 16x16 chunk -- one top block per column, not a full voxel dump. Same shape as AgentOps.getChunk. */
    static JsonObject worldChunkHeightmap(JsonObject msg) {
        LocalPlayer player = requirePlayer();
        Level level = player.level();
        int chunkX = msg.has("x") ? msg.get("x").getAsInt() : player.blockPosition().getX() >> 4;
        int chunkZ = msg.has("z") ? msg.get("z").getAsInt() : player.blockPosition().getZ() >> 4;

        JsonArray columns = new JsonArray();
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int worldX = chunkX * 16 + dx;
                int worldZ = chunkZ * 16 + dz;
                int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ);
                BlockPos topBlock = new BlockPos(worldX, surfaceY - 1, worldZ);
                JsonObject col = new JsonObject();
                col.addProperty("x", worldX);
                col.addProperty("z", worldZ);
                col.addProperty("surfaceY", surfaceY);
                col.addProperty("block", idOf(BuiltInRegistries.BLOCK.getKey(level.getBlockState(topBlock).getBlock())));
                columns.add(col);
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("chunkX", chunkX);
        result.addProperty("chunkZ", chunkZ);
        result.add("columns", columns);
        return result;
    }

    /**
     * focused/screenOpen -- lets the companion tell "nothing is happening because the OS window
     * lacks focus or a menu/pause screen is open" (Minecraft's tick loop halts entirely in either
     * case, silently no-op'ing every tick-driven bridge command) apart from an actual bug, instead
     * of guessing from unchanged position alone. See BridgeServer's class javadoc.
     */
    static JsonObject windowState() {
        Minecraft client = Minecraft.getInstance();
        JsonObject result = new JsonObject();
        result.addProperty("focused", client.isWindowActive());
        result.addProperty("screenOpen", client.screen != null);
        if (client.screen != null) result.addProperty("screenTitle", client.screen.getTitle().getString());
        return result;
    }

    /** singleplayer/server-address/dimension -- lets the companion resolve region/profile-scoped logic itself, mirroring RegionManager.currentProfileKey without needing that class client-side. */
    static JsonObject sessionInfo() {
        Minecraft client = Minecraft.getInstance();
        JsonObject result = new JsonObject();
        boolean singleplayer = client.hasSingleplayerServer();
        result.addProperty("singleplayer", singleplayer);
        if (!singleplayer) {
            ServerData server = client.getCurrentServer();
            if (server != null && server.ip != null) result.addProperty("serverAddress", server.ip);
        }
        if (client.player != null) {
            result.addProperty("dimension", client.player.level().dimension().identifier().toString());
        }
        return result;
    }

    /** Available singleplayer saves -- {worlds:[{name (save-folder id), displayName, lastPlayed (epoch millis)}, ...]}, so the companion can pick a target for system.resumeWorld without guessing folder names. See WorldResumer. */
    static JsonObject systemListWorlds() {
        return WorldResumer.listWorldsResult();
    }

    static JsonObject systemDifficulty() {
        return WorldResumer.difficultyResult();
    }

    /** All regions in the current profile -- name/parent/bounds (bounds omitted for the unbounded "global" region)/isGlobal, so a companion-side region editor can list what already exists. */
    static JsonObject regionList() {
        RegionProfile profile = RegionManager.get().currentProfile();
        JsonArray regions = new JsonArray();
        for (Region r : profile.regions.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", r.name);
            if (r.parent != null) o.addProperty("parent", r.parent);
            else o.add("parent", JsonNull.INSTANCE);
            if (!r.isGlobal()) {
                o.addProperty("minX", r.minX);
                o.addProperty("minY", r.minY);
                o.addProperty("minZ", r.minZ);
                o.addProperty("maxX", r.maxX);
                o.addProperty("maxY", r.maxY);
                o.addProperty("maxZ", r.maxZ);
            }
            o.addProperty("isGlobal", r.isGlobal());
            regions.add(o);
        }
        JsonObject result = new JsonObject();
        result.add("regions", regions);
        return result;
    }

    private static String idOf(Identifier id) {
        return id != null ? id.toString() : "unknown";
    }

    private static BlockPos readPos(JsonObject obj) {
        return new BlockPos(obj.get("x").getAsInt(), obj.get("y").getAsInt(), obj.get("z").getAsInt());
    }

    private static LocalPlayer requirePlayer() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) throw new IllegalStateException("no client player loaded");
        return player;
    }

    private static Level requireLevel() {
        Level level = Minecraft.getInstance().level;
        if (level == null) throw new IllegalStateException("no client world loaded");
        return level;
    }
}
