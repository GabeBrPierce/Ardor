package com.ardor.bridge;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.utils.IInputOverrideHandler;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Replaces the companion's hand-rolled A-star Navigator as the actual movement engine (see
 * TODO.md -- that pathfinder hit real limits: a 20,000-node search cap that can't plan long
 * routes in one shot, and "stuck for Nms" walking timeouts). Baritone plans in timeout-bounded
 * segments and plans the next segment ahead while executing the current one, so it doesn't need
 * one giant search up front. The companion still decides WHERE/WHEN to go (task/region/event
 * logic); this is purely "walk to this block," driven from the bridge like every other command --
 * same division of responsibility as every other bridge primitive.
 *
 * Baritone's own jar (baritone-api-fabric-1.18.0.jar) must be deployed as its OWN mod jar
 * alongside this mod's, not just referenced at compile time -- the files() dependency in
 * build.gradle only puts its classes on this mod's classpath for compiling against; Fabric Loader
 * still needs to discover and initialize Baritone's jar itself (its own fabric.mod.json,
 * entrypoints, mixins) for BaritoneAPI.getProvider() to return a real, working instance at
 * runtime. Confirmed live 2026-09-06: works once both jars sit side by side in mods/.
 */
public final class BaritoneNav {

    private BaritoneNav() {}

    private static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    /**
     * Called once from ArdorClient's CLIENT_STARTED hook. Fixes the recurring "hotbar keeps
     * snapping back to a tool/weapon" report: Baritone's own autoTool setting (confirmed real via
     * `javap` against baritone-api-fabric-1.18.0.jar -- Settings.autoTool, a Setting<Boolean>) was
     * never actually turned off anywhere in this codebase, so it kept re-equipping whatever IT
     * considered the best tool on every tick it was pathing/breaking a block, fighting both manual
     * hotbar changes and this mod's own ToolSelector/ensureToolFor tool-tier logic (game package) --
     * not just during the previously-fixed "digging through the Go-Here target block" case, but any
     * time Baritone needed to clear a block along ANY path (nav.goto, mine-via-Baritone, the
     * container-fetch/tool-fetch walks). Settings are process-global (BaritoneAPI.getSettings(),
     * confirmed via `javap` on BaritoneAPI -- there's no per-instance settings() on IBaritone), so
     * this only needs to run once per launch, not per goTo call.
     *
     * assumeExternalAutoTool (also confirmed real via javap) is set alongside it so Baritone still
     * plans path costs as if the correct tool were equipped, instead of treating every breakable
     * block as tool-less-slow -- it just stops being the one to actually swap the hotbar, since this
     * mod's own tool-tier pre-flight (PathfindingController.ensureToolFor) already is the "external
     * autotool" Baritone is being told to assume exists.
     */
    public static void configure() {
        var settings = BaritoneAPI.getSettings();
        settings.autoTool.value = false;
        settings.assumeExternalAutoTool.value = true;

        // "if a block is too high we should grab some dirt from nearby and build a tower from the
        // ground up to break it" -- Baritone already has real support for exactly this (its own
        // path search can place blocks to climb, "pillaring"), confirmed via javap:
        // Settings.allowPlace/allowParkourPlace (Setting<Boolean>) and acceptableThrowawayItems
        // (Setting<List<Item>>) all real. It was simply never enabled/stocked anywhere in this
        // codebase, so a target with no EXISTING ground-level approach (a log high in a tall tree,
        // say) had no way to be reached at all -- confirmed live as the "mine couldn't reach
        // oak_log" timeout report. allowPlace on lets Baritone place blocks as part of a path at
        // all; dirt specifically is added to acceptableThrowawayItems (a mutable List<Item> field,
        // copied rather than assumed appendable in place since its concrete type/mutability isn't
        // part of the public API contract) since PathfindingController.ensureScaffoldingMaterial
        // is what actually keeps dirt in the bot's inventory for this to draw on.
        //
        // "Use the stone we mined to build a stairway back up if we can't find a way back up
        // ourselves" -- same real mechanism covers this too, no separate hand-rolled staircase
        // builder needed: cobblestone/cobbled deepslate (the actual drops from mining stone/
        // deepslate, not the source blocks themselves, which aren't what ends up in the inventory)
        // are just more acceptable pillaring material. Baritone's own path search already only
        // resorts to placing blocks when a normal walking route doesn't exist, which is exactly
        // "if we can't find a way back up ourselves" -- BreakAreaController's own return-to-origin
        // walk (see its own doc) gets this for free by reusing the same walkThenRun/Baritone path.
        List<Item> extraThrowaway = List.of(Items.DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE);
        if (!settings.acceptableThrowawayItems.value.containsAll(extraThrowaway)) {
            List<Item> throwaway = new ArrayList<>(settings.acceptableThrowawayItems.value);
            for (Item item : extraThrowaway) {
                if (!throwaway.contains(item)) throwaway.add(item);
            }
            settings.acceptableThrowawayItems.value = throwaway;
        }
        settings.allowPlace.value = true;
        settings.allowParkourPlace.value = true;

        // "It considers a half slab a whole block and attempts to break it to attack enemies" --
        // confirmed real via javap against Settings.class: allowWalkOnBottomSlab is exactly this
        // toggle (walk onto/over a bottom slab as a normal, cheap step instead of treating its
        // partial collision box the same as a full solid block needing a jump or a break-through).
        // Never explicitly set anywhere in this codebase before, so it sat at Baritone's own
        // default -- whatever that default actually is, this makes the intended behavior explicit
        // rather than incidental.
        settings.allowWalkOnBottomSlab.value = true;
    }

    // Made public (was package-private): PathfindingController (package
    // com.ardor.game) calls this directly for the "home" verb -- see
    // TODO.md 2026-09-06.
    public static void goTo(JsonObject msg) {
        int x = msg.get("x").getAsInt(), y = msg.get("y").getAsInt(), z = msg.get("z").getAsInt();
        goTo(x, y, z);
    }

    /** Plain-Java overload for same-process callers (the Xaero's World Map "Go Here" mixin) that already have raw coordinates and shouldn't need to build a JsonObject just to immediately unpack it. */
    public static void goTo(int x, int y, int z) {
        baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(x, y, z));
    }

    /**
     * Unlike goTo's GoalBlock (an exact position WE already picked), GoalGetToBlock (confirmed real
     * via javap: takes the target position, isInGoal is satisfied from any adjacent/on-top tile --
     * standard Baritone "get within interaction range" semantics) lets Baritone's own path search
     * choose the final tile itself, including one that doesn't exist yet until Baritone places a
     * block to stand on getting there. PathfindingController.mineNext's tower-building fallback
     * needs exactly this: our own findReachableStandable only ever considers EXISTING solid ground,
     * so it can never propose "build a pillar" the way giving Baritone the raw target block can.
     */
    public static void goToBlock(BlockPos target) {
        baritone().getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(target));
    }

    /**
     * "Kill/Kill All/Follow don't pursue the entity" -- confirmed the actual cause: `follow`
     * walked via this project's own one-shot A* pathfinder (PathfindingController.pathTo),
     * re-planning only once the target drifts >3 blocks from where the LAST plan targeted, against
     * a target that's usually still moving by the time that plan finishes -- the exact "one giant
     * search up front, no notion of a moving goal" limitation this class's own javadoc already
     * names as the reason `mine`/`gohome` moved to Baritone; `follow` itself was flagged as a known
     * follow-up, never done. `attackUntilDead` (GameActionController) never walked toward the
     * target at all -- it only ever turned and swung, correct for GrindModeController's stationary
     * "sit and fight whatever wanders into view" use case, wrong for Kill/Kill All chasing
     * something that runs. Baritone's own IFollowProcess (confirmed real via javap against
     * baritone-api-fabric-1.18.0.jar) is built for exactly this: continuously re-paths toward
     * whatever currently matches the given predicate, no manual drift-threshold polling needed.
     * `e -> e == target` follows one specific entity by reference.
     */
    public static void followEntity(Entity target) {
        baritone().getFollowProcess().follow(e -> e == target);
    }

    /** Stops whatever followEntity() started -- IFollowProcess.cancel() is a real default method (confirmed via javap), not something this has to reimplement via an empty-match predicate. */
    public static void cancelFollow() {
        baritone().getFollowProcess().cancel();
    }

    // Made public (was package-private): PathfindingController needs to actually cancel a
    // Baritone path when its own timeout/give-up logic fires (mine-via-Baritone, 2026-09-06) --
    // confirmed live this was a real gap: without this, Baritone kept trying (visibly still
    // rotating/attempting movement) indefinitely in the background after our own polling gave up
    // and moved on, since nothing had ever told Baritone itself to stop.
    public static void stop() {
        baritone().getPathingBehavior().cancelEverything();
    }

    // Added for PathfindingController's mine-via-Baritone migration (2026-09-06, see TODO.md) --
    // plain-Java accessors for internal same-process callers so they don't have to build/parse
    // JSON just to read values status() already computes for the bridge. Additive only: goTo/stop/
    // status above are untouched.

    public static boolean isPathing() {
        return baritone().getPathingBehavior().isPathing();
    }

    public static boolean hasPath() {
        return baritone().getPathingBehavior().hasPath();
    }

    /** True if pos satisfies the currently-set goal (false if there is no goal at all). */
    public static boolean isAtGoal(BlockPos pos) {
        Goal goal = baritone().getPathingBehavior().getGoal();
        return goal != null && goal.isInGoal(pos);
    }

    /**
     * "DisableImplicitDestruction -- disable the mod deciding we should break these blocks to get
     * to the target." Baritone drives most movement now (mine's walk, follow, tool-fetch, Go Here)
     * and makes its own independent decision to break an inconvenient block along a path
     * (Settings.allowBreak) with no notion of Ardor's own region system at all. See
     * game.BaritoneRegionGate, which calls this every tick based on whichever region the player is
     * CURRENTLY in -- toggled here rather than left permanently off/on since a region boundary can
     * be crossed mid-path.
     */
    public static void setAllowBreak(boolean allowed) {
        BaritoneAPI.getSettings().allowBreak.value = allowed;
    }

    /**
     * Real hook Baritone itself uses to drive movement -- every tick it's pathing, Baritone calls
     * setInputForceState(JUMP/SPRINT/SNEAK/MOVE_FORWARD/etc, true/false) on this same handler to
     * actually move the player (confirmed via javap: IInputOverrideHandler is a real interface,
     * not an internal implementation detail). MovementVarianceController reads/overrides it
     * AFTER Baritone's own tick to layer human-like imprecision on top (misjumps, crouching near
     * risky terrain, speed variance) without touching Baritone's own path planning -- same
     * "override on top" pattern GameActionController's combat strafing already established
     * against Baritone-driven following, including that code's own acknowledged risk: whichever
     * tick listener runs second in a given tick wins for that tick.
     */
    public static IInputOverrideHandler inputOverride() {
        return baritone().getInputOverrideHandler();
    }

    /** {pathing, hasPath, atGoal?} -- the companion polls this instead of tracking position itself, same polling-friendly shape as everything else on this bridge. */
    static JsonObject status() {
        IPathingBehavior behavior = baritone().getPathingBehavior();
        JsonObject result = new JsonObject();
        result.addProperty("pathing", behavior.isPathing());
        result.addProperty("hasPath", behavior.hasPath());
        Goal goal = behavior.getGoal();
        if (goal != null) {
            LocalPlayer player = Minecraft.getInstance().player;
            result.addProperty("atGoal", player != null && goal.isInGoal(player.blockPosition()));
        }
        return result;
    }
}
