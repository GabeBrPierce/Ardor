package com.ardor.game;

import com.ardor.client.StatusIndicator;
import com.ardor.container.CacheSearch;
import com.ardor.container.CachedItem;
import com.ardor.container.ContainerFetchService;
import com.ardor.container.ContainerSource;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * "Build Blocks Within" -- the placing counterpart to BreakAreaController: fills every empty
 * position in a box, bottom layer to top layer (each layer swept in full first, mirroring
 * BreakAreaController's own top-to-bottom/boustrophedon order -- always building on solid ground
 * already placed, never stacking into thin air), drawing each block from a caller-supplied plan
 * (BuildBlockPlan, built by BuildBlocksScreen from what's in the player's inventory, nearby
 * registered containers, and nearby minable blocks of the same type).
 *
 * The plan is fully expanded into a flat, position-parallel queue of Steps up front (start()) --
 * simpler than picking "what to place next" live every step, and it means a shortfall is visible
 * immediately (fewer steps than positions) rather than discovered mid-run. A Step sourced from
 * inventory/containers only ever appears as many times as the plan's requested count; a Step whose
 * entry also allows environment mining is round-robined in to cover whatever's left after every
 * finite entry is exhausted, since mining more is a renewable source rather than a fixed count.
 *
 * No resume-after-restart support (unlike BreakAreaController) and no return-to-origin walk when
 * done -- not asked for; see TODO.md if that gap needs closing later.
 */
public final class BuildAreaController {

    private static final int MAX_POSITIONS = 2048;
    private static final int ENVIRONMENT_MINE_RADIUS = 32;

    /** One row of the caller's plan: how much of item to draw from where. useCount is the inventory+container amount committed (0 if neither is enabled); fromEnvironment means "also mine more of this exact block nearby once that runs out." */
    public record PlanEntry(Item item, int useCount, boolean fromInventory, boolean fromContainers, boolean fromEnvironment) {}

    private record Step(Item item, boolean fromInventory, boolean fromContainers, boolean fromEnvironment) {}

    private static volatile boolean active;
    private static Deque<BlockPos> queue = new ArrayDeque<>();
    private static Deque<Step> steps = new ArrayDeque<>();
    private static BlockBreaker breaker;
    private static int totalCount;
    private static int placedCount;
    private static BlockPos currentPos;

    private BuildAreaController() {}

    public static boolean isActive() {
        return active;
    }

    public static int totalCount() {
        return totalCount;
    }

    public static int placedCount() {
        return placedCount;
    }

    /**
     * Every AIR position within box, bottom layer up, boustrophedon-swept per layer -- see class
     * doc for why bottom-up (always building on something already solid). Capped at MAX_POSITIONS.
     * box.maxX/Y/Z are the AABB's EXCLUSIVE upper bound, same convention BreakAreaController.
     * enumerate fixed -- min/max computed the same way here.
     */
    public static List<BlockPos> enumerate(AABB box, Level level) {
        BlockPos min = BlockPos.containing(box.minX, box.minY, box.minZ);
        BlockPos max = BlockPos.containing(box.maxX - 1, box.maxY - 1, box.maxZ - 1);

        List<BlockPos> found = new ArrayList<>();
        for (int y = min.getY(); y <= max.getY(); y++) {
            boolean forward = true;
            for (int x = min.getX(); x <= max.getX(); x++) {
                int zStart = forward ? min.getZ() : max.getZ();
                int zEnd = forward ? max.getZ() : min.getZ();
                int zStep = forward ? 1 : -1;
                for (int z = zStart; forward ? z <= zEnd : z >= zEnd; z += zStep) {
                    if (found.size() >= MAX_POSITIONS) return found;
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level.getBlockState(pos).isAir()) found.add(pos);
                }
                forward = !forward;
            }
        }
        return found;
    }

    /** How many block-items across all currently-selected plan rows are committed from inventory/containers, ignoring environment mining's effectively-unlimited contribution -- what BuildBlocksScreen shows against the required total. */
    public static int committedCount(List<PlanEntry> plan) {
        int sum = 0;
        for (PlanEntry entry : plan) sum += entry.useCount();
        return sum;
    }

    public static boolean hasUnlimitedSource(List<PlanEntry> plan) {
        for (PlanEntry entry : plan) {
            if (entry.fromEnvironment()) return true;
        }
        return false;
    }

    /**
     * Expands plan into a flat, position-parallel Step queue (see class doc), clips positions to
     * however many Steps could actually be produced if the plan falls short, and starts filling.
     */
    public static void start(List<BlockPos> positions, List<PlanEntry> plan) {
        if (active) return;

        Deque<Step> expanded = new ArrayDeque<>();
        for (PlanEntry entry : plan) {
            for (int i = 0; i < entry.useCount(); i++) {
                expanded.add(new Step(entry.item(), entry.fromInventory(), entry.fromContainers(), entry.fromEnvironment()));
            }
        }
        // Beyond each entry's committed useCount, only mining can cover the rest -- inventory/
        // containers already contributed everything they had in the loop above, so these topup
        // steps go straight to environment sourcing rather than re-checking inventory.
        List<PlanEntry> unlimited = plan.stream().filter(PlanEntry::fromEnvironment).toList();
        int u = 0;
        while (expanded.size() < positions.size() && !unlimited.isEmpty()) {
            PlanEntry entry = unlimited.get(u % unlimited.size());
            expanded.add(new Step(entry.item(), false, false, true));
            u++;
        }

        int usable = Math.min(positions.size(), expanded.size());
        if (usable == 0) {
            StatusIndicator.show("Nothing to build -- no blocks selected.");
            return;
        }

        active = true;
        totalCount = usable;
        placedCount = 0;
        queue = new ArrayDeque<>(positions.subList(0, usable));
        steps = expanded;
        currentPos = null;
        breaker = new BlockBreaker();
        StatusIndicator.show("Building " + totalCount + " block(s)...");
        next();
    }

    public static void cancel() {
        active = false;
        queue.clear();
        steps.clear();
        if (breaker != null) breaker.cancel();
    }

    private static void guarded(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            System.err.println("[ardor] build area: stopped by error: " + e);
            active = false;
            if (breaker != null) breaker.cancel();
            StatusIndicator.show("Build area stopped by an error (" + placedCount + "/" + totalCount + " placed) -- " + e.getMessage());
        }
    }

    private static void next() {
        guarded(BuildAreaController::nextInner);
    }

    private static void nextInner() {
        if (!active) return;
        BlockPos pos = queue.poll();
        Step step = steps.poll();
        currentPos = pos;
        if (pos == null || step == null) {
            active = false;
            StatusIndicator.show("Built " + placedCount + "/" + totalCount + " block(s).");
            return;
        }
        if (Minecraft.getInstance().level == null || !Minecraft.getInstance().level.getBlockState(pos).isAir()) {
            next(); // already filled (e.g. a placed block's own physics update changed a neighbor) -- skip and continue
            return;
        }
        ensureHolding(step, () -> guarded(() -> walkAndPlace(pos, step.item())));
    }

    /** Inventory first (if this Step's entry allows it); else a registered container (if allowed); else mine one nearby (if allowed). Skips this position entirely if none apply -- "use inventory"/"use containers"/"break blocks" are real per-row on/off switches, not just a display hint, so a stock the row didn't opt into is left untouched even if it's sitting right there in the player's inventory. */
    private static void ensureHolding(Step step, Runnable onReady) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) { next(); return; }
        if (step.fromInventory() && countInInventory(player.getInventory(), step.item()) > 0) {
            onReady.run();
            return;
        }
        if (step.fromContainers()) {
            var found = findContainerWith(step.item());
            if (found != null) {
                ContainerFetchService.fetch(found.source, found.item,
                        stack -> onReady.run(),
                        failReason -> {
                            if (step.fromEnvironment()) mineOneNearby(step.item(), onReady);
                            else { System.err.println("[ardor] build area: couldn't fetch " + step.item() + ": " + failReason); currentPos = null; next(); }
                        });
                return;
            }
        }
        if (step.fromEnvironment()) {
            mineOneNearby(step.item(), onReady);
            return;
        }
        System.err.println("[ardor] build area: no source left for " + step.item() + ", skipping " + currentPos);
        currentPos = null;
        next();
    }

    private record ContainerHit(ContainerSource source, CachedItem item) {}

    /** Any registered container currently stocking item, regardless of distance -- BuildBlocksScreen already only shows/enables this checkbox for a type it found stocked somewhere. */
    private static ContainerHit findContainerWith(Item item) {
        String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
        LocalPlayer player = Minecraft.getInstance().player;
        BlockPos center = player != null ? player.blockPosition() : BlockPos.ZERO;
        for (CacheSearch.Group group : CacheSearch.groupedSearch("", true, true, 0, center)) {
            if (group.fromCommand() || !group.itemId().equals(itemId) || group.members().isEmpty()) continue;
            CacheSearch.Result m = group.members().get(0);
            return new ContainerHit(m.source(), m.item());
        }
        return null;
    }

    /** Walks to and mines the nearest block matching item's own block (BlockIndex, same index ScriptEngine.queryBlock uses) so it lands in inventory, then proceeds -- if the block's own drop isn't itself (e.g. stone -> cobblestone), the eventual place attempt fails loudly via GameActionController's own "not found in inventory" error rather than silently, a known simplification (see TODO.md). */
    private static void mineOneNearby(Item item, Runnable onReady) {
        if (!(item instanceof BlockItem blockItem)) { currentPos = null; next(); return; }
        Block block = blockItem.getBlock();
        LocalPlayer player = Minecraft.getInstance().player;
        Level level = player != null ? player.level() : null;
        if (player == null || level == null) { currentPos = null; next(); return; }

        List<BlockPos> nearest = BlockIndex.nearest(Set.of(block), player.blockPosition(), ENVIRONMENT_MINE_RADIUS, level, 1);
        if (nearest.isEmpty()) {
            System.err.println("[ardor] build area: no nearby " + block + " left to mine, skipping " + currentPos);
            currentPos = null;
            next();
            return;
        }
        BlockPos orePos = nearest.get(0);
        PathfindingController.walkThenRun(orePos, "couldn't reach " + orePos + " to mine building material",
                () -> breaker.breakBlock(orePos, () -> guarded(onReady)),
                failReason -> {
                    System.err.println("[ardor] build area: couldn't reach " + orePos + ": " + failReason);
                    currentPos = null;
                    next();
                });
    }

    private static void walkAndPlace(BlockPos pos, Item item) {
        PathfindingController.walkThenRun(pos, "couldn't reach " + pos + " while building",
                () -> guarded(() -> placeAt(pos, item)),
                failReason -> {
                    System.err.println("[ardor] build area: skipping " + pos + ": " + failReason);
                    currentPos = null;
                    next();
                });
    }

    private static final Direction[] SUPPORT_ORDER = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};

    /** The first solid neighbor to place against (below preferred, since bottom-up order means it's almost always already filled), or null if pos is fully surrounded by air -- genuinely floating, skipped rather than guessed at. */
    private static Direction pickSupportFacing(Level level, BlockPos pos) {
        for (Direction d : SUPPORT_ORDER) {
            if (!level.getBlockState(pos.relative(d)).isAir()) return d.getOpposite();
        }
        return null;
    }

    private static void placeAt(BlockPos pos, Item item) {
        Level level = Minecraft.getInstance().level;
        Direction facing = level != null ? pickSupportFacing(level, pos) : null;
        if (facing == null) {
            System.err.println("[ardor] build area: " + pos + " has no solid neighbor to place against, skipping");
            currentPos = null;
            next();
            return;
        }
        Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
        JsonObject action = new JsonObject();
        action.addProperty("action", "place");
        JsonObject block = new JsonObject();
        block.addProperty("id", itemId.toString());
        action.add("block", block);
        JsonObject position = new JsonObject();
        position.addProperty("x", pos.getX());
        position.addProperty("y", pos.getY());
        position.addProperty("z", pos.getZ());
        action.add("position", position);
        action.addProperty("facing", facing.getName());
        GameActionController.dispatch(action);
        placedCount++;
        currentPos = null;
        next();
    }

    private static int countInInventory(Inventory inv, Item item) {
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem() == item) total += stack.getCount();
        }
        return total;
    }
}
