package com.ardor.game;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ardor.bridge.BaritoneNav;
import com.ardor.client.StatusIndicator;
import com.ardor.config.ArdorConfig;
import com.ardor.macro.MacroPlayer;
import com.ardor.pathing.AStarPathfinder;
import com.ardor.region.RegionManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Dispatches the pathfinding-shaped slice of the Action IR (goto, follow,
 * mine, stop) to this project's own A* pathfinder + BlockBreaker, not
 * Baritone (see class history/TODO.md for why) -- EXCEPT mine and gohome,
 * both of which walk via BaritoneNav directly. gohome (added 2026-09-06
 * alongside HomeManager) was the first: it calls BaritoneNav.goTo and never
 * touches AStarPathfinder at all. mine's walk-to-target was migrated the
 * same way later that day (see TODO.md) after AStarPathfinder/PathExecutor's
 * own climb/step-up logic was confirmed live to oscillate forever near
 * elevated blocks (a leaf-collision tree canopy) instead of completing or
 * admitting failure -- BlockBreaker itself was never the problem, only the
 * walk there. goto/follow/shaft still go through AStarPathfinder/PathExecutor
 * unchanged; migrating them too is a follow-up (see TODO.md), not done here.
 *
 * mine's block search is a brute-force cube scan (BlockPos.betweenClosedStream),
 * capped at radius 48 -- fine for now, but a real perf concern at large radii;
 * see TODO.md.
 */
public final class PathfindingController {

    private static final int MAX_SEARCH_NODES = 20_000;
    private static final int MAX_MINE_RADIUS = 48;

    private static final PathExecutor executor = new PathExecutor();
    private static final BlockBreaker breaker = new BlockBreaker();
    private static final MacroPlayer macroPlayer = new MacroPlayer();

    private static Entity followTarget;
    private static boolean followLoopRegistered;

    // mine's walk-to-target, driven via BaritoneNav instead of AStarPathfinder/PathExecutor --
    // see TODO.md 2026-09-06 (the elevated-tree hover bug). Same "one shared in-flight state,
    // lazily-registered single tick loop" shape as followLoopRegistered above.
    private static final long BARITONE_MINE_TIMEOUT_MS = 20_000; // generous: Baritone plans in segments, real distances vary
    private static final long BARITONE_MINE_GRACE_MS = 1_000; // let Baritone start calculating before "not pathing" counts as "gave up"
    private static final long BARITONE_SCAFFOLD_TIMEOUT_MS = 45_000; // building a pillar block-by-block is slower than an equivalent walk
    private static BlockPos baritoneNavGoal;
    private static long baritoneNavStarted;
    private static long baritoneNavDeadline;
    private static Runnable baritoneNavOnArrive;
    private static String baritoneNavFailContext;
    private static boolean baritoneNavLoopRegistered;
    private static java.util.function.Consumer<String> baritoneNavOnFailed; // nullable -- see walkThenRun

    private PathfindingController() {}

    public static boolean handles(String verb) {
        return verb.equals("goto") || verb.equals("follow") || verb.equals("mine") || verb.equals("stop")
                || verb.equals("shaft") || verb.equals("digHole") || verb.equals("macro") || verb.equals("sethome") || verb.equals("gohome");
    }

    /** True while a goto/mine/macro is actively running. Deliberately does NOT count an active follow (open-ended by design, never "finishes" on its own) -- see TaskRunner. */
    public static boolean isBusy() {
        return executor.isActive() || breaker.isActive() || macroPlayer.isActive() || baritoneNavGoal != null;
    }

    /** The entity a `follow` command is currently tracking, or null. Used by DomainEvents for OnFollowedEntityTakesDamage. */
    public static Entity getFollowTarget() {
        return followTarget;
    }

    /**
     * "We should look for a way to determine if we are able to fly" -- Abilities.mayfly (granted
     * by creative/spectator mode, or an operator/command) is the real, direct vanilla signal for
     * "flight is currently permitted," confirmed via javap against Abilities.class in this build
     * (public boolean mayfly/flying fields). Not a full "could obtain flight" check (an elytra in
     * inventory doesn't set this -- gliding isn't the same capability as sustained creative-style
     * flight, and nothing currently distinguishes them); just what's directly queryable today.
     * No caller yet -- added for future pathfinding/movement logic to branch on, per the request.
     */
    public static boolean canFly() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.getAbilities().mayfly;
    }

    public static boolean isFlying() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.getAbilities().flying;
    }

    /**
     * Direct entity-instance entry point for follow, bypassing selector resolution -- for callers
     * (SingleSelectionMode's entity sub-wheel) that already have the exact Entity from a crosshair
     * hit rather than a selector string. Same shape as GameActionController.attackEntityUntilDead.
     *
     * Walks via BaritoneNav.followEntity (its native IFollowProcess), not this project's own
     * AStarPathfinder/PathExecutor -- confirmed live ("Follow doesn't pursue") that re-planning a
     * one-shot search only once the target drifts >3 blocks was too fragile against a genuinely
     * moving target, the same class of problem mine/gohome already left that pathfinder for. See
     * BaritoneNav.followEntity's own doc for the full story.
     */
    public static void followEntity(Entity target) {
        followTarget = target;
        BaritoneNav.followEntity(target);
        ensureFollowWatchdog();
    }

    // "If we hit an error... we should bounce it back to the LLM so they can self correct."
    // isBusy() alone can't distinguish "finished successfully" from "gave up" -- an async
    // operation (mine, shaft) going idle again looked identical to TaskRunner either way, so a
    // give-up that only logged+returned (no exception) read as a silent success: the command
    // "completed" with nothing to show for it, and nothing ever told the LLM it needed a
    // different approach. This is the signal TaskRunner polls once isBusy() goes false, in
    // addition to (not instead of) any command that fails synchronously via a thrown exception.
    private static volatile String lastFailureReason;

    private static void markFailed(String reason) {
        lastFailureReason = reason;
    }

    /** Null if the most recent async operation actually succeeded; otherwise why it didn't. Consumed once, then cleared -- see TaskRunner.tickInner. */
    public static String consumeLastFailure() {
        String reason = lastFailureReason;
        lastFailureReason = null;
        return reason;
    }

    public static void dispatch(JsonObject action) {
        String verb = action.get("action").getAsString();
        // executor/breaker/macroPlayer are shared singletons -- dispatching a second
        // goto/mine/shaft/macro while one is still running doesn't queue, it silently
        // overwrites the first's in-flight path/callback (confirmed live 2026-09-01:
        // two "mine oak_log" DO: lines fired back-to-back left the bot standing at the
        // first tree's already-mined base, staring at nothing -- the second command's
        // pathTo() had clobbered the first's executor state before its break callback
        // ever ran). TaskRunner-driven callers (Task Manager, orchestration, and now
        // ResponseHandler's say_do queue -- see below) never hit this, since they wait
        // for isBusy() to clear before dispatching the next command; this guard is only
        // for direct-dispatch callers (agent channel, single_command mode) that don't.
        if (NEEDS_IDLE.contains(verb) && isBusy()) {
            throw new IllegalStateException(verb + ": already busy with another movement/mining command");
        }
        switch (verb) {
            case "goto":   handleGoto(action); return;
            case "follow": handleFollow(action); return;
            case "mine":   handleMine(action); return;
            case "stop":   handleStop(); return;
            case "shaft":  handleShaft(action); return;
            case "digHole": handleDigHole(action); return;
            case "macro":  handleMacro(action); return;
            case "sethome": handleSetHome(); return;
            case "gohome": handleGoHome(); return;
            default: throw new IllegalArgumentException("PathfindingController does not handle: " + verb);
        }
    }

    private static final Set<String> NEEDS_IDLE = Set.of("goto", "mine", "shaft", "digHole", "macro");

    /** macro "name" -- replays a recorded macro (see macro/MacroPlayer, //ardor record start/stop). "I want the AI to have access to these and be able to run them." */
    private static void handleMacro(JsonObject action) {
        String name = action.get("name").getAsString();
        macroPlayer.play(name, null);
    }

    private static final Direction[] SPIRAL_DIRS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    /**
     * "mine a large column/mineshaft," full V2 spec: straight shaft by
     * default, or a 1-block-wide spiral staircase (spiral:y) circling a
     * central un-mined pillar -- rotates N/E/S/W around the player's
     * starting XZ, descending one Y level per quarter-turn, which is also
     * why it's walkable afterward with zero special-casing: the resulting
     * path is just diagonal adjacent-cell steps, exactly what
     * BlockWorldMovement's existing diagonal-movement support already
     * handles for `pathTo`. Either shape only digs through
     * config.breakableBlocks (same list/philosophy as dig-through-obstacles)
     * and stops loudly on anything else. dump:@x,y,z (optional) walks to a
     * container at that position once the shaft/staircase is complete and
     * empties the player's inventory into it.
     */
    private static void handleShaft(JsonObject action) {
        int depth = action.get("depth").getAsInt();
        boolean spiral = action.has("spiral") && action.get("spiral").getAsBoolean();
        BlockPos dumpTarget = action.has("dumpInto") ? readPos(action.getAsJsonObject("dumpInto")) : null;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) throw new IllegalStateException("No client player loaded");
        Set<Block> breakable = resolveBreakableBlocks();
        BlockPos start = player.blockPosition();
        if (spiral) {
            digSpiralShaft(start, start.getY(), 0, depth, breakable, dumpTarget);
        } else {
            digShaft(start, depth, breakable, dumpTarget);
        }
    }

    private static Set<Block> resolveBreakableBlocks() {
        Set<Block> result = new HashSet<>();
        for (String id : ArdorConfig.get().breakableBlocks) {
            BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id)).ifPresent(result::add);
        }
        return result;
    }

    private static void digShaft(BlockPos pos, int remaining, Set<Block> breakable, BlockPos dumpTarget) {
        if (remaining <= 0) {
            logAndShow("shaft: reached target depth at " + pos, "Shaft complete");
            if (dumpTarget != null) dumpInventoryInto(dumpTarget);
            return;
        }
        BlockPos below = pos.below();
        digThenContinue(below, breakable, () -> pathTo(below, () -> digShaft(below, remaining - 1, breakable, dumpTarget)));
    }

    private static void digSpiralShaft(BlockPos center, int startY, int step, int totalSteps, Set<Block> breakable, BlockPos dumpTarget) {
        if (step >= totalSteps) {
            logAndShow("shaft: spiral staircase complete, " + totalSteps + " steps down from " + center, "Spiral shaft complete");
            if (dumpTarget != null) dumpInventoryInto(dumpTarget);
            return;
        }
        Direction dir = SPIRAL_DIRS[step % 4];
        int y = startY - step - 1;
        BlockPos target = new BlockPos(center.getX() + dir.getStepX(), y, center.getZ() + dir.getStepZ());

        digThenContinue(target, breakable, () ->
                digThenContinue(target.above(), breakable, () ->
                        pathTo(target, () -> digSpiralShaft(center, startY, step + 1, totalSteps, breakable, dumpTarget))));
    }

    /** Breaks pos if it's solid-and-breakable, continues immediately if it's already open, or stops the whole shaft (loudly) if it's solid and NOT breakable. */
    private static void digThenContinue(BlockPos pos, Set<Block> breakable, Runnable onOpen) {
        Level level = Minecraft.getInstance().player.level();
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            onOpen.run();
            return;
        }
        if (!breakable.contains(state.getBlock())) {
            String reason = "shaft: hit non-breakable " + blockName(state.getBlock()) + " at " + pos + ", stopping";
            logAndShow(reason, "Shaft stopped: hit " + blockName(state.getBlock()));
            markFailed(reason);
            return;
        }
        breaker.breakBlock(pos, () -> {
            String breakFailure = breaker.consumeLastFailure();
            if (breakFailure != null) {
                markFailed("shaft: " + breakFailure);
                return;
            }
            onOpen.run();
        });
    }

    /**
     * Walks to a standable spot next to containerPos (if one exists -- if
     * not, deposits anyway rather than silently doing nothing) and empties
     * the player's inventory into it. Direct Container field writes, not
     * the real container-click packet protocol -- same simplification
     * already accepted for equip/drop in GameActionController (documented
     * there as "likely fine in singleplayer, NOT verified for multiplayer").
     */
    private static void dumpInventoryInto(BlockPos containerPos) {
        dumpInventoryInto(containerPos, null);
    }

    /** onDone (nullable) fires once the deposit has actually happened -- added for BreakAreaController, which needs to wait for a mid-run dump to finish (a separate movement system, PathExecutor via pathTo, from its own Baritone-driven walkThenRun) before resuming, rather than starting both at once. */
    private static void dumpInventoryInto(BlockPos containerPos, Runnable onDone) {
        Level level = Minecraft.getInstance().player.level();
        BlockWorldMovement model = new BlockWorldMovement(level);
        BlockPos standPos = findReachableStandable(containerPos, model, level);
        if (standPos != null) {
            pathTo(standPos, () -> {
                depositInto(containerPos);
                if (onDone != null) onDone.run();
            });
        } else {
            depositInto(containerPos);
            if (onDone != null) onDone.run();
        }
    }

    private static void depositInto(BlockPos containerPos) {
        LocalPlayer player = Minecraft.getInstance().player;
        RotationUtil.lookAtExact(player, containerPos);
        Level level = player.level();
        BlockEntity be = level.getBlockEntity(containerPos);
        if (!(be instanceof Container container)) {
            String reason = "shaft: dump target " + containerPos + " is not a container";
            logAndShow(reason, "No container to dump into");
            markFailed(reason);
            return;
        }
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            inv.setItem(i, insertIntoContainer(container, stack));
        }
        container.setChanged();
        logAndShow("shaft: dumped inventory into container at " + containerPos, "Dumped materials into storage");
    }

    /** Fills empty slots and tops up matching stacks; returns whatever didn't fit (empty if it all fit). */
    private static ItemStack insertIntoContainer(Container container, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty()) {
                if (!container.canPlaceItem(i, remaining)) continue;
                container.setItem(i, remaining.copy());
                remaining = ItemStack.EMPTY;
            } else if (ItemStack.isSameItemSameComponents(slot, remaining)) {
                int room = slot.getMaxStackSize() - slot.getCount();
                if (room <= 0) continue;
                int move = Math.min(room, remaining.getCount());
                slot.grow(move);
                remaining.shrink(move);
            }
        }
        return remaining;
    }

    private static final int HOLE_DEPTH = 3;

    /**
     * "dig a three deep hole and seal the top" -- the classic no-shelter
     * survival trick. Digs straight down 3 blocks (same digThenContinue/
     * pathTo shape as digShaft, just fixed-depth and with a completion
     * callback instead of shaft's own dump-on-finish), then seals the
     * entrance once the player has actually walked down into the bottom.
     * brave:true additionally opens a 1-wide head-height gap toward
     * whichever direction the player was facing when the command was
     * issued (captured up front, not re-read later) -- the "feeling brave"
     * mob-trap variant.
     */
    private static void handleDigHole(JsonObject action) {
        boolean brave = action.has("brave") && action.get("brave").getAsBoolean();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) throw new IllegalStateException("No client player loaded");
        Set<Block> breakable = resolveBreakableBlocks();
        BlockPos start = player.blockPosition();
        Direction facing = player.getDirection();
        digHoleStep(start, HOLE_DEPTH, breakable, () -> sealHoleEntrance(start, () -> {
            if (brave) {
                openHoleTrap(start, facing, breakable);
            } else {
                logAndShow("hole: dug and sealed at " + start, "Hole dug and sealed");
            }
        }));
    }

    private static void digHoleStep(BlockPos pos, int remaining, Set<Block> breakable, Runnable onComplete) {
        if (remaining <= 0) {
            onComplete.run();
            return;
        }
        BlockPos below = pos.below();
        digThenContinue(below, breakable, () -> pathTo(below, () -> digHoleStep(below, remaining - 1, breakable, onComplete)));
    }

    /**
     * Places a block at the original entrance (start) once the player has
     * dug/walked down into the hole below it. Checks the inventory for ANY
     * placeable block (BlockItem) -- the exact type doesn't matter, it just
     * needs to plug the hole -- and clicks the face of a solid horizontal
     * neighbor of start that borders it, same useItemOn/BlockHitResult
     * mechanics as GameActionController.handlePlace / BridgeActionHandlers.
     * blockPlace (can't call those directly, private/different-class, but
     * the placement math is copied as-is).
     */
    private static void sealHoleEntrance(BlockPos start, Runnable onSealed) {
        LocalPlayer player = Minecraft.getInstance().player;
        Level level = player.level();
        Inventory inv = player.getInventory();

        int slot = -1;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem) { slot = i; break; }
        }
        if (slot < 0) {
            String reason = "hole: no placeable block in inventory to seal entrance at " + start;
            logAndShow(reason, "No block to seal the hole with");
            markFailed(reason);
            return;
        }

        Direction dirToNeighbor = null;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!level.getBlockState(start.relative(d)).isAir()) { dirToNeighbor = d; break; }
        }
        if (dirToNeighbor == null) {
            String reason = "hole: no solid neighbor at " + start + " to seal against";
            logAndShow(reason, "Can't seal the hole");
            markFailed(reason);
            return;
        }

        selectSlotInHand(slot);
        BlockPos against = start.relative(dirToNeighbor);
        Direction hitFace = dirToNeighbor.getOpposite();
        RotationUtil.lookAtExact(player, start);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(against), hitFace, against, false);
        Minecraft.getInstance().gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);

        logAndShow("hole: sealed entrance at " + start, "Hole sealed");
        onSealed.run();
    }

    /** Same hotbar-swap mechanics as GameActionController.selectItemInHand, just addressed by slot index instead of item id (sealHoleEntrance already found the slot, doesn't need the item-id lookup that helper does). */
    private static void selectSlotInHand(int slotIndex) {
        LocalPlayer player = Minecraft.getInstance().player;
        Inventory inv = player.getInventory();
        if (Inventory.isHotbarSlot(slotIndex)) {
            inv.setSelectedSlot(slotIndex);
            return;
        }
        int hotbar = inv.getSelectedSlot();
        ItemStack held = inv.getItem(hotbar);
        ItemStack toEquip = inv.getItem(slotIndex);
        inv.setItem(hotbar, toEquip);
        inv.setItem(slotIndex, held);
    }

    /**
     * "feeling brave" mob-trap opening: breaks the block at head-height
     * (1 below true ground level) toward `facing`, and the ground-level
     * block directly above that on the same side -- a 1-wide gap that's
     * only open at head height, not at a mob's foot level, so approaching
     * mob pathfinding walks into it and falls to the bottom next to the
     * player instead of just standing at the sealed lip.
     */
    private static void openHoleTrap(BlockPos start, Direction facing, Set<Block> breakable) {
        BlockPos headHeight = start.below(1).relative(facing);
        BlockPos groundLevel = start.relative(facing);
        digThenContinue(headHeight, breakable, () ->
                digThenContinue(groundLevel, breakable, () ->
                        logAndShow("hole: brave opening broken toward " + facing + " at " + start, "Hole trap opening ready")));
    }

    /** sethome -- records the player's current position as this profile's home (see HomeManager). */
    private static void handleSetHome() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) throw new IllegalStateException("No client player loaded");
        HomeManager.get().setHome(RegionManager.currentProfileKey(), player.blockPosition());
    }

    /** home -- paths back to the profile's stored home via Baritone (this mod-side controller has no other Baritone integration; nav.goto/nav.stop/nav.status on the bridge are the only other callers of BaritoneNav). */
    private static void handleGoHome() {
        BlockPos home = HomeManager.get().getHome(RegionManager.currentProfileKey())
                .orElseThrow(() -> new IllegalStateException("No home set for this profile -- use sethome first"));
        JsonObject pos = new JsonObject();
        pos.addProperty("x", home.getX());
        pos.addProperty("y", home.getY());
        pos.addProperty("z", home.getZ());
        BaritoneNav.goTo(pos);
    }

    private static void handleGoto(JsonObject action) {
        JsonElement dest = action.get("destination");
        BlockPos target = dest.isJsonObject()
                ? readPos(dest.getAsJsonObject())
                : requireEntity(dest.getAsString(), "goto").blockPosition();
        pathTo(target, null);
    }

    private static void pathTo(BlockPos target, Runnable onArrive) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) throw new IllegalStateException("No client player loaded");
        BlockPos start = player.blockPosition();
        BlockWorldMovement model = new BlockWorldMovement(player.level());
        List<BlockPos> path = AStarPathfinder.findPath(start, target, model, MAX_SEARCH_NODES);
        if (path == null) throw new IllegalStateException("No path found to " + target);
        executor.follow(path, model, onArrive);
    }

    /**
     * mine's walk-to-target, via BaritoneNav instead of pathTo/PathExecutor (see TODO.md
     * 2026-09-06 -- PathExecutor's own climb/step-up logic was the actual cause of the
     * elevated-tree hover bug; BlockBreaker itself was never the problem). standPos is expected
     * to already be a reachable-with-line-of-sight standing spot (see findReachableStandable,
     * reused as-is -- it already does exactly the "find an adjacent standable position" job this
     * needed, no separate heuristic required). onArrive fires once Baritone reports the player
     * actually at standPos; on timeout or Baritone giving up, markFailed(...) instead, matching
     * every other mine failure path's convention.
     */
    private static void baritoneNavTo(BlockPos standPos, String failContext, Runnable onArrive) {
        baritoneNavTo(standPos, failContext, onArrive, null);
    }

    private static void baritoneNavTo(BlockPos standPos, String failContext, Runnable onArrive, java.util.function.Consumer<String> onFailed) {
        JsonObject pos = new JsonObject();
        pos.addProperty("x", standPos.getX());
        pos.addProperty("y", standPos.getY());
        pos.addProperty("z", standPos.getZ());
        BaritoneNav.goTo(pos);
        beginBaritoneNav(failContext, onArrive, onFailed, standPos, BARITONE_MINE_TIMEOUT_MS);
    }

    /**
     * mineNext's tower-building fallback: unlike baritoneNavTo (an exact position WE already
     * picked via findReachableStandable, which only ever considers EXISTING solid ground), this
     * hands Baritone the raw target block via GoalGetToBlock and lets its OWN path search choose
     * the final tile -- including one it builds itself mid-route (pillaring), given
     * BaritoneNav.configure()'s allowPlace/allowParkourPlace and a real throwaway block actually in
     * hand (see ensureScaffoldingMaterial). A longer timeout than ordinary walking: placing blocks
     * one at a time while climbing is meaningfully slower than a normal walk of the same distance.
     */
    private static void baritoneNavToBlock(BlockPos target, String failContext, Runnable onArrive) {
        BaritoneNav.goToBlock(target);
        beginBaritoneNav(failContext, onArrive, null, target, BARITONE_SCAFFOLD_TIMEOUT_MS);
    }

    private static void beginBaritoneNav(String failContext, Runnable onArrive, java.util.function.Consumer<String> onFailed, BlockPos logGoal, long timeoutMs) {
        long now = System.currentTimeMillis();
        baritoneNavGoal = logGoal;
        baritoneNavStarted = now;
        baritoneNavDeadline = now + timeoutMs;
        baritoneNavOnArrive = onArrive;
        baritoneNavFailContext = failContext;
        baritoneNavOnFailed = onFailed;
        ensureBaritoneNavLoop();
    }

    /**
     * Walks to a reachable spot near targetPos (if one isn't already at hand) and then runs
     * onArrive -- the exact same walk-then-act shape obtainAdequateTool already uses for "go get
     * a tool from a container," generalized so other features can reuse it instead of
     * reimplementing baritoneNavTo/findReachableStandable themselves. Added for the container
     * item-fetch feature (see container.ContainerFetchService): fetching a cached item from a
     * physical source or a sub-container's physical parent needs exactly this "walk there, then
     * do the actual take" flow. onFailed(reason) fires instead of onArrive if Baritone can't get
     * there (unreachable, timed out) -- unlike mine's own failure path, this does NOT also go
     * through markFailed/consumeLastFailure, since fetch callers (screens, bridge) get an explicit
     * callback instead of polling TaskRunner-style.
     */
    public static void walkThenRun(BlockPos targetPos, String failContext, Runnable onArrive, java.util.function.Consumer<String> onFailed) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            onFailed.accept("no client player loaded");
            return;
        }
        Level level = player.level();
        BlockWorldMovement model = new BlockWorldMovement(level);
        BlockPos standPos = findReachableStandable(targetPos, model, level);
        if (standPos != null) {
            baritoneNavTo(standPos, failContext, onArrive, onFailed);
        } else {
            onArrive.run();
        }
    }

    private static void ensureBaritoneNavLoop() {
        if (baritoneNavLoopRegistered) return;
        baritoneNavLoopRegistered = true;
        // Same crash-avoidance shape as ensureFollowLoop's tick listener -- an uncaught exception
        // here crashes the whole client (see TODO.md, PushToTalk's tick handler).
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                baritoneNavTick(client);
            } catch (RuntimeException e) {
                System.err.println("[ardor] mine: baritone nav tick failed: " + e);
                baritoneNavGoal = null;
                baritoneNavOnArrive = null;
            }
        });
    }

    private static void baritoneNavTick(Minecraft client) {
        if (baritoneNavGoal == null) return;
        LocalPlayer player = client.player;
        if (player == null) return;

        if (BaritoneNav.isAtGoal(player.blockPosition())) {
            Runnable onArrive = baritoneNavOnArrive;
            baritoneNavGoal = null;
            baritoneNavOnArrive = null;
            baritoneNavOnFailed = null;
            onArrive.run();
            return;
        }

        long now = System.currentTimeMillis();
        boolean gaveUp = now - baritoneNavStarted > BARITONE_MINE_GRACE_MS
                && !BaritoneNav.isPathing() && !BaritoneNav.hasPath();
        boolean timedOut = now > baritoneNavDeadline;
        if (gaveUp || timedOut) {
            String reason = baritoneNavFailContext + " (" + (gaveUp ? "baritone gave up" : "timed out")
                    + " heading to " + baritoneNavGoal + ")";
            java.util.function.Consumer<String> onFailed = baritoneNavOnFailed;
            if (onFailed != null) {
                onFailed.accept(reason);
            } else {
                logAndShow("mine: " + reason, "Couldn't reach block to mine");
                markFailed("mine: " + reason);
            }
            // Without this, Baritone itself keeps trying in the background indefinitely -- confirmed
            // live: our own polling gave up here, but nothing had told Baritone to actually stop, so
            // it kept visibly rotating/attempting the same path forever. See BaritoneNav.stop()'s javadoc.
            BaritoneNav.stop();
            baritoneNavGoal = null;
            baritoneNavOnArrive = null;
            baritoneNavOnFailed = null;
        }
    }

    private static void handleFollow(JsonObject action) {
        followEntity(requireEntity(action.get("target").getAsString(), "follow"));
    }

    /** Baritone gives up on its own (target out of the world, etc.) without telling us -- this just clears our own bookkeeping (getFollowTarget()/OnFollowedEntityTakesDamage) once the target's gone; the walking itself is entirely Baritone's IFollowProcess now, no polling loop needed here. */
    private static void ensureFollowWatchdog() {
        if (followLoopRegistered) return;
        followLoopRegistered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                if (followTarget != null && !followTarget.isAlive()) {
                    followTarget = null;
                    BaritoneNav.cancelFollow();
                }
            } catch (RuntimeException e) {
                System.err.println("[ardor] follow watchdog failed: " + e);
            }
        });
    }

    private static void handleMine(JsonObject action) {
        JsonObject blockRef = action.getAsJsonObject("block");
        Block target = resolveBlock(blockRef.get("id").getAsString());
        int count = action.has("count") ? action.get("count").getAsInt() : 1;
        int radius = Math.min(
                action.has("searchRadius") ? (int) action.get("searchRadius").getAsDouble() : 32,
                MAX_MINE_RADIUS);
        BlockPos origin = action.has("searchOrigin") ? readPos(action.getAsJsonObject("searchOrigin")) : null;
        ensureToolFor(target, () -> mineNext(target, count, radius, origin, null));
    }

    private static final int MAX_MINE_CANDIDATES = 20;

    private static void mineNext(Block target, int remaining, int radius, BlockPos origin) {
        mineNext(target, remaining, radius, origin, null);
    }

    /** onAllMined (nullable) fires once, only on genuine full completion (remaining reaches 0) -- not on any failure path, those already report via markFailed/lastFailureReason like every other path in this file. Added for the tool-tier acquisition chain below, which needs to know when mining a batch of ore/logs actually finished. */
    private static void mineNext(Block target, int remaining, int radius, BlockPos origin, Runnable onAllMined) {
        mineNext(target, remaining, radius, origin, onAllMined, true);
    }

    /** allowScaffoldFallback=false only for ensureScaffoldingMaterial's own internal dirt-gathering call -- a defensive bound against that call re-entering the fallback it exists to serve, not a real-world case (dirt is always ground-level and trivially reachable in practice). */
    private static void mineNext(Block target, int remaining, int radius, BlockPos origin, Runnable onAllMined, boolean allowScaffoldFallback) {
        if (remaining <= 0) {
            if (onAllMined != null) onAllMined.run();
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        Level level = player.level();
        BlockPos center = origin != null ? origin : player.blockPosition();
        List<BlockPos> candidates = findNearestBlocks(center, target, radius, level, MAX_MINE_CANDIDATES);

        if (candidates.isEmpty()) {
            // Was a silent `return` -- confirmed live it produced "command dispatched
            // successfully, nothing visibly happens" with zero signal anywhere. Now also
            // registers as a real TaskRunner failure (markFailed), not just a log line, so
            // orchestration mode actually finds out and can try something else -- "can't find a
            // spider" is exactly this path.
            String reason = "no " + blockName(target) + " found within " + radius + " blocks of " + center;
            logAndShow(reason, "Couldn't find " + blockName(target) + " nearby");
            markFailed(reason);
            return;
        }

        // Was: only the single nearest match was checked, and a whole command gave
        // up the moment THAT one happened to be unreachable (underwater, walled in,
        // etc), even when other matches close by were perfectly fine. Confirmed live,
        // repeatedly: this was the actual cause of "found dirt, can't reach it" on
        // dirt that had other easily-reachable dirt nearby. Now tries candidates in
        // distance order until one actually has a standable spot next to it.
        BlockWorldMovement model = new BlockWorldMovement(level);
        for (BlockPos ore : candidates) {
            BlockPos standPos = findReachableStandable(ore, model, level);
            if (standPos != null) {
                System.err.println("[ardor] mine: heading to " + standPos + " via Baritone to break " + blockName(target) + " at " + ore);
                String failContext = "couldn't reach " + blockName(target) + " at " + ore;
                baritoneNavTo(standPos, failContext, () -> breaker.breakBlock(ore, () -> {
                    String breakFailure = breaker.consumeLastFailure();
                    if (breakFailure != null) {
                        markFailed("mine: " + breakFailure);
                        return;
                    }
                    mineNext(target, remaining - 1, radius, origin, onAllMined, allowScaffoldFallback);
                }));
                return;
            }
        }

        if (!allowScaffoldFallback) {
            String reason = "found " + candidates.size() + " " + blockName(target) + " candidate(s) near " + center
                    + " but none had a reachable standing spot";
            logAndShow(reason, "Found it but can't reach it");
            markFailed(reason);
            return;
        }

        // None of the candidates had an EXISTING ground-level approach within reach+line-of-sight --
        // likely just too high (a log partway up a tall tree) rather than genuinely unreachable.
        // findReachableStandable can only ever offer a position that's ALREADY solid ground; it can
        // never propose "build a pillar to a tile that doesn't exist yet." Baritone's own path
        // search can (allowPlace/allowParkourPlace, configured in BaritoneNav.configure()), given a
        // GoalGetToBlock instead of our own pre-picked exact GoalBlock, and a real throwaway block
        // actually in hand first (ensureScaffoldingMaterial) -- same "close the gap, then retry"
        // shape ensureToolFor already uses for tools. Only the nearest candidate: each attempt is a
        // real, possibly slow walk-and-build, not a cheap geometric check like the loop above.
        BlockPos nearest = candidates.get(0);
        System.err.println("[ardor] mine: no ground-level approach to " + nearest + ", trying to build up to it");
        String failContext = "couldn't reach " + blockName(target) + " at " + nearest + " even by building up to it";
        ensureScaffoldingMaterial(() -> baritoneNavToBlock(nearest, failContext, () -> breaker.breakBlock(nearest, () -> {
            String breakFailure = breaker.consumeLastFailure();
            if (breakFailure != null) {
                markFailed("mine: " + breakFailure);
                return;
            }
            mineNext(target, remaining - 1, radius, origin, onAllMined, allowScaffoldFallback);
        })));
    }

    private static final int SCAFFOLD_TARGET_COUNT = 12; // generous slack for a tall tree; unused blocks just stay in inventory
    private static final int SCAFFOLD_SEARCH_RADIUS = 24;

    /**
     * Baritone can only pillar up to an elevated target if it's actually holding real blocks to
     * place (BaritoneNav.configure() enables allowPlace/allowParkourPlace and lists dirt in
     * acceptableThrowawayItems) -- same gap ensureToolFor closes for tools, just for scaffolding
     * material. Mines nearby dirt if the inventory doesn't already have enough; dirt specifically
     * since it's common and needs no tool to mine (requiresCorrectToolForDrops() is false for it,
     * same base case ensurePlanks' logs rely on). allowScaffoldFallback=false on this call: dirt
     * itself is always ground-level, so it should never need this same fallback for its own sake.
     */
    private static void ensureScaffoldingMaterial(Runnable onReady) {
        int have = countInInventory(Items.DIRT);
        if (have >= SCAFFOLD_TARGET_COUNT) {
            onReady.run();
            return;
        }
        mineNext(Blocks.DIRT, SCAFFOLD_TARGET_COUNT - have, SCAFFOLD_SEARCH_RADIUS, null, onReady, false);
    }

    // ------------------------------------------------------- tool-tier pre-flight (2026-09-10)
    //
    // Closes the gap flagged live: nothing anywhere in this codebase reasoned about "does this
    // block need a specific pickaxe tier, and do I actually have one" before mining -- Baritone's
    // own autoTool would just grab whatever ToolSelector ranked best (see its corrected javadoc),
    // which can be flatly wrong (a sword) with nothing stopping the attempt. See TODO.md for what
    // was verified via javap vs. assumed. Bounded to pickaxe-tier ore blocks -- verified nothing in
    // this jar's block-tag data gates axe/shovel/hoe blocks on a material tier the way ore/obsidian-
    // family blocks are gated on pickaxe tier, so this deliberately doesn't try to generalize beyond
    // pickaxes.

    private static final int TOOL_CONTAINER_SEARCH_RADIUS = 12; // ContainerSearch caps its own max separately
    private static final int TOOL_ORE_SEARCH_RADIUS = 32; // matches mine's own default searchRadius
    private static final int MAX_PLANK_ROUNDS = 3; // bail rather than loop forever if no oak_log is findable at all

    /**
     * Pre-flight gate for `mine`: if the target block requires a pickaxe tier the bot doesn't
     * currently hold anything correct for, tries to close that gap (nearby containers, then mining/
     * smelting/crafting one from scratch) before calling onReady (the real mineNext). Never lets
     * mineNext run against a block it's doomed to fail on for lack of the right tool -- if the gap
     * can't be closed, fails loudly via markFailed and never calls onReady, same convention as every
     * other mine failure path in this file.
     *
     * Package-private (not private): BreakAreaController (Area Selection's "Break Blocks Within")
     * reuses this directly, once per distinct block type in its selection, rather than
     * reimplementing tool-tier pre-flight for a bulk multi-type break.
     */
    static void ensureToolFor(Block target, Runnable onReady) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) throw new IllegalStateException("No client player loaded");
        BlockState state = target.defaultBlockState();
        ToolTierRequirements.Tier required = ToolTierRequirements.requiredTier(state);
        if (required == null || ToolTierRequirements.hasAdequateTool(player.getInventory(), state)) {
            onReady.run();
            return;
        }
        logAndShow("need a " + required + "-tier tool for " + blockName(target) + ", don't have one -- trying to get one first",
                "Need a better tool for " + blockName(target) + " first");
        obtainAdequateTool(state, required, onReady);
    }

    /** Inventory already ruled out by the caller (ensureToolFor). Tries nearby containers next, then falls through to building one from scratch -- unless ExcludeImplicitItemRetrieval is set for the player's current region, in which case the container search is skipped entirely and this falls straight through to crafting (still subject to ExcludeImplicitItemManufacturing's own separate gate in craftToolFromScratch). */
    private static void obtainAdequateTool(BlockState targetState, ToolTierRequirements.Tier required, Runnable onReady) {
        LocalPlayer player = Minecraft.getInstance().player;
        Level level = player.level();
        Predicate<ItemStack> match = ToolTierRequirements.adequateToolPredicate(targetState);

        boolean retrievalExcluded = RegionManager.get().hasFlag(RegionManager.currentProfileKey(), player.blockPosition(), r -> r.excludeImplicitItemRetrieval);
        BlockPos containerPos = retrievalExcluded ? null
                : ContainerSearch.findNearbyContainerWithItem(level, player.blockPosition(), TOOL_CONTAINER_SEARCH_RADIUS, match);
        if (containerPos == null) {
            craftToolFromScratch(required, onReady);
            return;
        }

        BlockWorldMovement model = new BlockWorldMovement(level);
        BlockPos standPos = findReachableStandable(containerPos, model, level);
        Runnable fetch = () -> {
            ItemStack found = ContainerSearch.takeMatching(level, containerPos, match);
            if (found == null) {
                // Changed between the search and now -- fall through to crafting instead of failing outright.
                craftToolFromScratch(required, onReady);
                return;
            }
            Minecraft.getInstance().player.getInventory().add(found);
            logAndShow("fetched a suitable tool from a container at " + containerPos, "Got a tool from storage");
            onReady.run();
        };
        if (standPos != null) {
            baritoneNavTo(standPos, "couldn't reach a container at " + containerPos + " to fetch a tool", fetch);
        } else {
            fetch.run();
        }
    }

    /** Mines the lower-tier ore, smelts it if needed, and crafts the pickaxe -- all via the same mine/craft/smelt verbs an LLM caller would use, not a separate mechanism. Bounded to at most 5 recursive tiers (WOOD..DIAMOND); NETHERITE is refused outright since netherite tools are a smithing-table upgrade of an existing diamond tool, not a normal CraftingRecipe this project's craft verb can produce. */
    private static void craftToolFromScratch(ToolTierRequirements.Tier tier, Runnable onReady) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && RegionManager.get().hasFlag(RegionManager.currentProfileKey(), player.blockPosition(), r -> r.excludeImplicitItemManufacturing)) {
            String reason = "need to craft/smelt a " + tier + " tool, but ExcludeImplicitItemManufacturing is set for this region";
            logAndShow(reason, "Can't auto-craft here");
            markFailed(reason);
            return;
        }
        if (tier == ToolTierRequirements.Tier.NETHERITE) {
            String reason = "need a netherite tool, but netherite tools are a smithing-table upgrade of a diamond "
                    + "tool (not a normal crafting recipe) -- unsupported, no diamond-tool-plus-smithing-table flow exists here";
            logAndShow(reason, "Can't auto-craft a netherite tool");
            markFailed(reason);
            return;
        }
        // Every real pickaxe recipe is a 3-wide/3-tall shape (XXX/.#./.#.) -- confirmed via the
        // real recipe JSONs, see TODO.md. RealCraftingController (not the old instant
        // GameActionController.dispatch(craftAction(...))) -- confirmed live that the instant path
        // produces a "ghost" item that vanishes the moment the server corrects the client back to
        // truth, even in singleplayer; this walks to (or obtains+places) a real crafting table and
        // crafts via actual slot-click packets instead.
        ensureSticks(2, () -> ensureTierMaterial(tier, 3, () ->
                RealCraftingController.craft(tier.pickaxeId, 1,
                        () -> {
                            if (countInInventory(resolveItem(tier.pickaxeId)) < 1) {
                                String reason = "crafted " + tier.pickaxeId + " but it's not in the inventory afterward";
                                logAndShow(reason, "Tool craft didn't produce anything");
                                markFailed(reason);
                                return;
                            }
                            logAndShow("crafted a " + tier.pickaxeId + " to continue", "Crafted a new tool");
                            onReady.run();
                        },
                        reason -> {
                            logAndShow("couldn't craft " + tier.pickaxeId + ": " + reason, "Couldn't craft a tool");
                            markFailed("couldn't craft " + tier.pickaxeId + ": " + reason);
                        })));
    }

    private static void ensureSticks(int needed, Runnable onReady) {
        if (countInInventory(Items.STICK) >= needed) {
            onReady.run();
            return;
        }
        ensurePlanks(2, MAX_PLANK_ROUNDS, () -> {
            try {
                GameActionController.dispatch(craftAction("minecraft:stick", needed, false));
            } catch (RuntimeException e) {
                String reason = "couldn't craft sticks: " + e.getMessage();
                logAndShow(reason, "Couldn't craft sticks");
                markFailed(reason);
                return;
            }
            if (countInInventory(Items.STICK) < needed) {
                String reason = "crafted sticks but still don't have " + needed;
                logAndShow(reason, "Not enough sticks");
                markFailed(reason);
                return;
            }
            onReady.run();
        });
    }

    /** Package-private entry point for RealCraftingController (needs planks to craft a crafting_table before it can do anything else) -- same chain craftToolFromScratch/ensureChests already use, just under the default attempt budget. */
    static void ensurePlanksPublic(int needed, Runnable onReady) {
        ensurePlanks(needed, MAX_PLANK_ROUNDS, onReady);
    }

    /** Base case of the whole recursive chain: logs need no tool at all to mine (requiresCorrectToolForDrops() is false for them), so this is where the recursion actually bottoms out. */
    private static void ensurePlanks(int needed, int attemptsLeft, Runnable onReady) {
        if (countInInventory(Items.OAK_PLANKS) >= needed) {
            onReady.run();
            return;
        }
        if (attemptsLeft <= 0) {
            String reason = "couldn't gather " + needed + " oak_planks (ran out of nearby oak_log attempts)";
            logAndShow(reason, "Couldn't find enough wood");
            markFailed(reason);
            return;
        }
        mineNext(resolveBlock("minecraft:oak_log"), 2, TOOL_ORE_SEARCH_RADIUS, null, () -> {
            try {
                GameActionController.dispatch(craftAction("minecraft:oak_planks", needed, false));
            } catch (RuntimeException e) {
                String reason = "couldn't craft oak_planks: " + e.getMessage();
                logAndShow(reason, "Couldn't craft planks");
                markFailed(reason);
                return;
            }
            ensurePlanks(needed, attemptsLeft - 1, onReady);
        });
    }

    /**
     * "Obtain a container that can fit the area's contents" (BreakAreaController's capacity-
     * shortfall path) -- look for an existing chest first, else ensure enough planks (reusing the
     * exact same ensurePlanks chain craftToolFromScratch/ensureSticks already use: mine oak_log if
     * short, craft into oak_planks) then craft chestCount chests via RealCraftingController (a
     * real crafting table, real slot-click packets) -- NOT the old instant
     * GameActionController.dispatch(craftAction(...)), which is exactly what produced the live
     * "ghost chest" report this was rewritten to fix (visible client-side the instant it's called,
     * but never told to the integrated server, so it vanished the moment a real interaction
     * corrected the client back to truth).
     */
    public static void ensureChests(int chestCount, Runnable onReady) {
        if (countInInventory(Items.CHEST) >= chestCount) {
            onReady.run();
            return;
        }
        ensurePlanks(chestCount * 8, MAX_PLANK_ROUNDS, () ->
                RealCraftingController.craft("minecraft:chest", chestCount,
                        () -> {
                            if (countInInventory(Items.CHEST) < chestCount) {
                                String reason = "crafted chest(s) but fewer than " + chestCount + " ended up in inventory";
                                logAndShow(reason, "Chest craft came up short");
                                markFailed(reason);
                                return;
                            }
                            onReady.run();
                        },
                        reason -> {
                            logAndShow("couldn't craft chest: " + reason, "Couldn't craft a chest");
                            markFailed("couldn't craft chest: " + reason);
                        }));
    }

    /** Package-private (was private): BreakAreaController periodically dumps the bot's inventory into a placed chest while breaking a large area, same "walk there (or deposit anyway if unreachable), empty the inventory" behavior shaft/hole digging already use for their own dumpTarget. onDone lets the caller resume its OWN walking (a different movement system, Baritone) only after this one finishes, rather than both running at once. */
    static void dumpInventoryIntoContainer(BlockPos containerPos, Runnable onDone) {
        dumpInventoryInto(containerPos, onDone);
    }

    /** Ensures `needed` of the tier's crafting material (planks/cobblestone/ingot/gem) is in the inventory, mining+smelting the lower-tier ore if it isn't -- recursing into ensureToolFor for the ORE's own tool requirement, so e.g. mining iron_ore first makes sure a stone(+) pickaxe is on hand, discovered the same real-tag-data way as the original target block, not assumed. */
    private static void ensureTierMaterial(ToolTierRequirements.Tier tier, int needed, Runnable onReady) {
        Item materialItem = resolveItem(tierMaterialId(tier));
        if (countInInventory(materialItem) >= needed) {
            onReady.run();
            return;
        }
        if (tier == ToolTierRequirements.Tier.WOOD) {
            ensurePlanks(needed, MAX_PLANK_ROUNDS, onReady);
            return;
        }
        Block ore = resolveBlock(tierOreId(tier));
        ensureToolFor(ore, () -> mineNext(ore, needed, TOOL_ORE_SEARCH_RADIUS, null, () -> afterMiningTierOre(tier, needed, onReady)));
    }

    private static void afterMiningTierOre(ToolTierRequirements.Tier tier, int needed, Runnable onReady) {
        String rawId = tierRawItemId(tier);
        if (rawId != null) {
            int haveRaw = countInInventory(resolveItem(rawId));
            if (haveRaw <= 0) {
                String reason = "mined " + tier + "-tier ore but got no " + rawId + " out of it";
                logAndShow(reason, "Ore didn't drop what was expected");
                markFailed(reason);
                return;
            }
            try {
                GameActionController.dispatch(smeltAction(rawId, haveRaw));
            } catch (RuntimeException e) {
                String reason = "couldn't smelt " + rawId + ": " + e.getMessage();
                logAndShow(reason, "Couldn't smelt ore");
                markFailed(reason);
                return;
            }
        }
        String materialId = tierMaterialId(tier);
        if (countInInventory(resolveItem(materialId)) < needed) {
            String reason = "still don't have " + needed + " " + materialId + " after mining/smelting";
            logAndShow(reason, "Still not enough material for the tool");
            markFailed(reason);
            return;
        }
        onReady.run();
    }

    /** The item the pickaxe recipe actually consumes for this tier -- confirmed against the real recipe JSONs bundled in the client jar (data/minecraft/recipe/*_pickaxe.json), not assumed: every tier's recipe keys "X" to a *_tool_materials item tag, and each of those tags (data/minecraft/tags/item/*_tool_materials.json) has exactly one plain-item member except wooden (any planks) and stone (cobblestone/blackstone/cobbled_deepslate, of which this only ever produces/checks cobblestone). See TODO.md. */
    private static String tierMaterialId(ToolTierRequirements.Tier tier) {
        return switch (tier) {
            case WOOD -> "minecraft:oak_planks";
            case STONE -> "minecraft:cobblestone";
            case COPPER -> "minecraft:copper_ingot";
            case IRON -> "minecraft:iron_ingot";
            case DIAMOND -> "minecraft:diamond";
            case NETHERITE -> "minecraft:netherite_ingot"; // unreachable -- craftToolFromScratch refuses NETHERITE before this is ever consulted
        };
    }

    /** The block to mine to obtain that material (WOOD mines logs via ensurePlanks instead; NETHERITE is refused before this is consulted). Deliberately only the overworld surface-ore variant, not the deepslate one -- see TODO.md. */
    private static String tierOreId(ToolTierRequirements.Tier tier) {
        return switch (tier) {
            case STONE -> "minecraft:stone";
            case COPPER -> "minecraft:copper_ore";
            case IRON -> "minecraft:iron_ore";
            case DIAMOND -> "minecraft:diamond_ore";
            default -> throw new IllegalStateException("tierOreId: no ore mapping for " + tier);
        };
    }

    /** Null means "no smelting needed, the mined item IS the material" (stone's cobblestone, diamond's raw gem) -- confirmed against the real loot_table/blocks/*.json for each ore (copper_ore/iron_ore drop raw_copper/raw_iron; diamond_ore drops diamond directly), and the real recipe/*_ingot_from_smelting_raw_*.json smelting recipes for the two that need it. See TODO.md. */
    private static String tierRawItemId(ToolTierRequirements.Tier tier) {
        return switch (tier) {
            case COPPER -> "minecraft:raw_copper";
            case IRON -> "minecraft:raw_iron";
            default -> null;
        };
    }

    private static int countInInventory(Item item) {
        Inventory inv = Minecraft.getInstance().player.getInventory();
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem() == item) total += stack.getCount();
        }
        return total;
    }

    private static JsonObject craftAction(String itemId, int count, boolean useCraftingTable) {
        JsonObject a = new JsonObject();
        a.addProperty("action", "craft");
        JsonObject item = new JsonObject();
        item.addProperty("id", itemId);
        item.addProperty("count", count);
        a.add("item", item);
        if (useCraftingTable) a.addProperty("useCraftingTable", true);
        return a;
    }

    /** No fuel field -- GameActionController.handleSmelt's own documented simplification ("omitting fuel consumes none") means this always proceeds without needing coal on hand, same as any other caller of the smelt verb that omits fuel. */
    private static JsonObject smeltAction(String inputId, int count) {
        JsonObject a = new JsonObject();
        a.addProperty("action", "smelt");
        JsonObject input = new JsonObject();
        input.addProperty("id", inputId);
        input.addProperty("count", count);
        a.add("input", input);
        return a;
    }

    private static Item resolveItem(String id) {
        return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id))
                .orElseThrow(() -> new IllegalArgumentException("unknown item id " + id));
    }

    private static List<BlockPos> findNearestBlocks(BlockPos center, Block target, int radius, Level level, int limit) {
        BlockPos min = center.offset(-radius, -radius, -radius);
        BlockPos max = center.offset(radius, radius, radius);
        return BlockPos.betweenClosedStream(min, max)
                .filter(pos -> level.getBlockState(pos).getBlock() == target)
                .map(BlockPos::immutable)
                .sorted(Comparator.comparingDouble(pos -> pos.distSqr(center)))
                .limit(limit)
                .toList();
    }

    private static String blockName(Block target) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(target);
        return id != null ? id.toString() : target.toString();
    }

    private static void logAndShow(String logMessage, String statusMessage) {
        System.err.println("[ardor] mine: " + logMessage);
        StatusIndicator.show(statusMessage);
    }

    private static final double MINE_REACH = 4.5; // vanilla survival block-interaction range -- matches BlockBreaker.MAX_REACH_SQ
    private static final int MINE_REACH_SEARCH_RADIUS = 5; // must cover MINE_REACH plus some slack for eye-height offset
    private static final double STANDING_EYE_HEIGHT = 1.62; // vanilla Player standing-pose eye height

    /**
     * A real player doesn't need to touch a block to break it -- just be
     * within reach (a sphere around the eyes, ~4.5 blocks in survival) with
     * a clear line of sight to it, exactly like holding left-click while
     * looking at something a few blocks away. The original version of this
     * method only ever checked the 6 touching faces, which is far more
     * restrictive than real reach and would give up on blocks a player
     * could easily mine from a few steps back (a floating block, one behind
     * a corner where only the 6 immediate faces happen to be unstandable,
     * etc). Searches every standable position within MINE_REACH_SEARCH_RADIUS,
     * keeps the ones actually within MINE_REACH of the block once standing
     * there, verifies an unobstructed raycast (BlockGetter.clip, the same
     * API a real crosshair hit-test uses) from that position's eye height
     * to the block, and returns the closest one that passes both checks.
     */
    private static BlockPos findReachableStandable(BlockPos target, BlockWorldMovement model, Level level) {
        BlockPos min = target.offset(-MINE_REACH_SEARCH_RADIUS, -MINE_REACH_SEARCH_RADIUS, -MINE_REACH_SEARCH_RADIUS);
        BlockPos max = target.offset(MINE_REACH_SEARCH_RADIUS, MINE_REACH_SEARCH_RADIUS, MINE_REACH_SEARCH_RADIUS);

        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!model.isStandable(pos)) continue;
            double eyeX = pos.getX() + 0.5, eyeY = pos.getY() + STANDING_EYE_HEIGHT, eyeZ = pos.getZ() + 0.5;
            double dx = (target.getX() + 0.5) - eyeX, dy = (target.getY() + 0.5) - eyeY, dz = (target.getZ() + 0.5) - eyeZ;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > MINE_REACH * MINE_REACH || distSq >= bestDistSq) continue;
            if (!hasLineOfSight(level, eyeX, eyeY, eyeZ, target)) continue;
            best = pos.immutable();
            bestDistSq = distSq;
        }
        return best;
    }

    private static boolean hasLineOfSight(Level level, double eyeX, double eyeY, double eyeZ, BlockPos target) {
        Vec3 from = new Vec3(eyeX, eyeY, eyeZ);
        Vec3 to = Vec3.atCenterOf(target);
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, Minecraft.getInstance().player));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    private static void handleStop() {
        executor.cancel();
        breaker.cancel();
        macroPlayer.cancel();
        followTarget = null;
        // BaritoneNav.stop() actually cancels Baritone's own in-progress path -- without this,
        // dropping our own tracking state here isn't enough, since Baritone keeps trying in the
        // background regardless of whether this controller is still watching it (confirmed live).
        // cancelFollow() separately, since IFollowProcess keeps its own predicate/state and could
        // reassert itself on a later tick after a plain cancelEverything() -- not confirmed live,
        // but cheap insurance given cancel() is a real, cheap no-op when nothing was following.
        BaritoneNav.stop();
        BaritoneNav.cancelFollow();
        baritoneNavGoal = null;
        baritoneNavOnArrive = null;
        baritoneNavOnFailed = null;
        GameActionController.stopAttacking();
        GameActionController.cancelWait();
    }

    private static BlockPos readPos(JsonObject pos) {
        return new BlockPos(
                (int) Math.floor(pos.get("x").getAsDouble()),
                (int) Math.floor(pos.get("y").getAsDouble()),
                (int) Math.floor(pos.get("z").getAsDouble())
        );
    }

    private static Block resolveBlock(String id) {
        // getValue() silently falls back to air for an unknown id (BLOCK is a
        // defaulted registry) -- confirmed live: the model emitted the nonexistent
        // "minecraft:grass" (real id is grass_block; "grass" hasn't existed since
        // the 1.13 flattening), which resolved to air with zero error, so the mine
        // action searched for "the nearest air" instead of failing loudly.
        // getOptional() doesn't have that fallback -- empty means genuinely absent.
        return BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id))
                .orElseThrow(() -> new IllegalArgumentException("mine: unknown block id " + id));
    }

    private static Entity requireEntity(String selector, String forAction) {
        Entity entity = SelectorResolver.resolveOne(selector);
        if (entity == null) throw new IllegalStateException(forAction + ": no entity matched " + selector);
        return entity;
    }
}
