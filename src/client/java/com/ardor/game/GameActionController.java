package com.ardor.game;

import com.ardor.bridge.BaritoneNav;
import com.ardor.client.ArdorMasterToggle;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Dispatches the non-pathfinding verbs (place, equip, attack, drop, use,
 * chat, craft, smelt) that PathfindingController doesn't handle.
 *
 * Inventory manipulation here (equip's slot swap, drop's slot removal,
 * craft/smelt's ingredient consumption) is done via direct client-side
 * Inventory calls, not the container-click packet protocol a real player
 * action sends. Likely fine in singleplayer (client and integrated server
 * share process); NOT verified to stay in sync with a real multiplayer
 * server. Flagged in TODO.md.
 *
 * craft/smelt additionally read recipes from the integrated server's
 * RecipeManager (Minecraft.getSingleplayerServer().getRecipeManager()) --
 * as of this MC version the client itself no longer holds a full recipe
 * list (ClientRecipeContainer only carries recipe-book item-tag data), so
 * this only works in singleplayer, same caveat as above.
 */
public final class GameActionController {

    private GameActionController() {}

    // No init-time register() of its own (ticking is lazy, via ensureAttackTicker/ensureWaitTicker) --
    // this class can't be loaded without attackUntilDead already having run, so cancel() is always live.
    static {
        ArdorMasterToggle.register(GameActionController::stopAttacking);
    }

    private static volatile boolean attacking = false;
    private static volatile Entity attackTarget;
    private static int ticksUntilNextAttack;
    private static boolean attackTickerRegistered = false;

    // Strafe-while-fighting ("add strafing when we have ample food -- running and jumping at the
    // same time") -- see applyStrafe/restoreStrafeInput below for the full explanation, including
    // the real, unverified-without-a-live-test risk of this fighting Baritone's own movement
    // control (mitigated, not eliminated, by only strafing once BaritoneNav.isPathing() is false).
    private static final int FOOD_STRAFE_THRESHOLD = 18; // out of 20 -- "ample," not merely "not hungry"
    private static final int STRAFE_FLIP_TICKS = 15; // roughly how often to reverse strafe direction
    private static final int STRAFE_JUMP_CHANCE_DENOM = 25; // ~1-in-N per tick while strafing without a sword -- irregular, not metronomic
    private static boolean strafingRight;
    private static int ticksUntilStrafeFlip;
    private static boolean strafeInputActive;
    private static ClientInput savedInputForStrafe;

    private static volatile int waitTicksRemaining;
    private static boolean waitTickerRegistered = false;

    public static boolean handles(String verb) {
        return switch (verb) {
            case "place", "equip", "attack", "drop", "use", "chat", "command", "wait", "craft", "smelt" -> true;
            default -> false;
        };
    }

    /** True while an `attack until:dead` loop or a `wait` is still running. Every other verb here completes synchronously within dispatch(), so there's nothing else to track -- see TaskRunner. */
    public static boolean isBusy() {
        return attacking || waitTicksRemaining > 0;
    }

    public static void dispatch(JsonObject action) {
        String verb = action.get("action").getAsString();
        switch (verb) {
            case "place":  handlePlace(action); return;
            case "equip":  handleEquip(action); return;
            case "attack": handleAttack(action); return;
            case "drop":   handleDrop(action); return;
            case "use":    handleUse(action); return;
            case "chat":   handleChat(action); return;
            case "command": handleCommand(action); return;
            case "wait":   handleWait(action); return;
            case "craft":  handleCraft(action); return;
            case "smelt":  handleSmelt(action); return;
            default: throw new IllegalArgumentException("GameActionController does not handle: " + verb);
        }
    }

    /** Plain tick-count pause -- same single-persistent-ticker pattern as attackUntilDead, for the same reason: calling this again before a previous wait finished should just extend/replace it, not stack up listeners. */
    private static void handleWait(JsonObject action) {
        waitTicksRemaining = (int) Math.round(action.get("seconds").getAsDouble() * 20);
        ensureWaitTicker();
    }

    public static void cancelWait() {
        waitTicksRemaining = 0;
    }

    private static void ensureWaitTicker() {
        if (waitTickerRegistered) return;
        waitTickerRegistered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (waitTicksRemaining > 0) waitTicksRemaining--;
        });
    }

    public static void stopAttacking() {
        attackTarget = null;
        attacking = false;
        BaritoneNav.cancelFollow();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.setSprinting(false); // don't leave the player stuck sprinting once a fight ends
            restoreStrafeInput(mc.player);
        }
    }

    /** Direct entity-instance entry point for attackUntilDead, bypassing selector resolution -- for callers (GrindModeController) that already have the exact Entity in hand from a raycast/crosshair pick rather than a selector string. */
    public static void attackEntityUntilDead(Entity target) {
        attackUntilDead(target);
    }

    private static void handlePlace(JsonObject action) {
        JsonObject block = action.getAsJsonObject("block");
        BlockPos target = readPos(action.getAsJsonObject("position"));
        Direction facing = action.has("facing")
                ? Direction.valueOf(action.get("facing").getAsString().toUpperCase())
                : Direction.UP;
        BlockPos against = target.relative(facing.getOpposite());

        selectItemInHand(block.get("id").getAsString());

        LocalPlayer player = Minecraft.getInstance().player;
        RotationUtil.lookAtExact(player, target);
        Vec3 hitVec = Vec3.atCenterOf(against); // approximate -- exact hit point affects stair/slab orientation, not verified precise
        BlockHitResult hit = new BlockHitResult(hitVec, facing, against, false);
        Minecraft.getInstance().gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
    }

    private static void handleEquip(JsonObject action) {
        JsonObject item = action.getAsJsonObject("item");
        String slot = action.get("slot").getAsString();
        if (!slot.equals("hand")) {
            throw new UnsupportedOperationException(
                    "equip: only 'hand' is implemented -- off_hand/armor slots need container click simulation");
        }
        selectItemInHand(item.get("id").getAsString());
    }

    /** Puts the named item in the active hotbar slot, swapping it in from elsewhere in the inventory if needed. */
    private static void selectItemInHand(String itemId) {
        LocalPlayer player = Minecraft.getInstance().player;
        Inventory inv = player.getInventory();
        Item item = resolveItem(itemId);
        int slotIndex = inv.findSlotMatchingItem(new ItemStack(item));
        if (slotIndex < 0) throw new IllegalStateException("selectItemInHand: " + itemId + " not found in inventory");

        if (Inventory.isHotbarSlot(slotIndex)) {
            HotbarUtil.selectSlot(player, slotIndex);
            return;
        }

        // Same client-only-mutation bug ToolSelector.equipBestTool had (see its own doc for the
        // full story) -- a real SWAP click, not a direct inv.setItem/inv.setItem pair, so the
        // server actually agrees on what's held. InventoryMenu's slot numbering matches raw
        // Inventory storage indices 9-35 directly (confirmed via javap); slotIndex is guaranteed
        // >= 9 here since the hotbar case already returned above.
        int hotbar = inv.getSelectedSlot();
        Minecraft.getInstance().gameMode.handleContainerInput(
                player.inventoryMenu.containerId, slotIndex, hotbar, ContainerInput.SWAP, player);
    }

    /** ITEM.getValue() silently falls back to air for an unknown id -- see PathfindingController.resolveBlock for how that surfaced live. getOptional() doesn't have that fallback. */
    private static Item resolveItem(String id) {
        return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id))
                .orElseThrow(() -> new IllegalArgumentException("unknown item id " + id));
    }

    private static void handleAttack(JsonObject action) {
        Entity target = requireEntity(action.get("target").getAsString(), "attack");
        String until = action.has("until") ? action.get("until").getAsString() : "once";
        if (!until.equals("dead")) {
            attackOnce(target);
            return;
        }
        attackUntilDead(target);
    }

    private static final float ATTACK_TURN_RATE = 25f; // degrees/tick -- see RotationUtil; fastest of the three since combat tracking needs to keep up with a moving target

    // "I want bell curve distribution on attacks" -- a perfectly even 10-tick metronome is the kind
    // of artificial regularity real timing never has (long-tailed, irregular gaps -- see the
    // human-mouse-movement/click-timing research this was built alongside). Sampled fresh each swing
    // from a normal distribution instead of a constant, clamped to a sane range so a rare extreme
    // sample can't produce a near-instant double-hit or a multi-second stall.
    private static final Random RANDOM = new Random();
    private static final double ATTACK_INTERVAL_MEAN_TICKS = 10.0;
    private static final double ATTACK_INTERVAL_STDDEV_TICKS = 1.5;
    private static final int ATTACK_INTERVAL_MIN_TICKS = 6;
    private static final int ATTACK_INTERVAL_MAX_TICKS = 14;

    private static int sampleAttackIntervalTicks() {
        double sample = ATTACK_INTERVAL_MEAN_TICKS + RANDOM.nextGaussian() * ATTACK_INTERVAL_STDDEV_TICKS;
        long rounded = Math.round(sample);
        return (int) Math.max(ATTACK_INTERVAL_MIN_TICKS, Math.min(ATTACK_INTERVAL_MAX_TICKS, rounded));
    }

    /**
     * "If you jump, just before landing you land a critical hit... if you are running when you
     * attack you give knockback... we should run sooner, especially if we don't have a sword."
     * Verified via javap -c disassembly of Player.attack/canCriticalAttack in this build (not
     * assumed): a crit requires fallDistance>0 && !onGround && !isSprinting() (among other
     * always-true-in-this-context checks) -- crits and sprint-knockback are mutually exclusive per
     * swing, since sprinting explicitly disables crits. So the choice is per-fight, not per-swing:
     * holding a sword (sword damage already high, crit adds real value) leans into jump-timed
     * crits and stays off sprint; anything else (bare hand/weak tool -- low DPS regardless, so
     * knockback/distance-control matters more than the marginal crit bonus) leans into sprinting
     * for the knockback bonus instead. There's no public accessor for the private fallDistance
     * field this build exposes, so the crit path can't hard-verify "definitely falling" before
     * swinging -- it jumps a few ticks ahead of the scheduled swing (CRIT_JUMP_LEAD_TICKS) so the
     * swing STATISTICALLY lands during the fall phase of that hop, and swings on schedule either
     * way rather than stalling the whole attack loop waiting for a state it can't directly observe.
     */
    private static boolean hasSwordEquipped(LocalPlayer player) {
        var id = BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem());
        return id != null && id.getPath().endsWith("_sword");
    }

    private static final int CRIT_JUMP_LEAD_TICKS = 5; // jump this many ticks before the scheduled swing, so the swing lands mid-fall

    // Randomized aim point: "look at somewhere on the mob's hit box," not always the exact eye
    // position -- re-rolled periodically (not every tick, which would read as a vibrating point)
    // so it looks like natural re-fixation instead of either a frozen stare or constant twitching.
    private static Entity aimOffsetTarget;
    private static double aimOffsetX, aimOffsetY, aimOffsetZ;
    private static int ticksUntilAimReroll;

    private static final double AIM_REROLL_MEAN_TICKS = 25.0;
    private static final double AIM_REROLL_STDDEV_TICKS = 8.0;
    private static final int AIM_REROLL_MIN_TICKS = 10;
    private static final int AIM_REROLL_MAX_TICKS = 50;

    private static int sampleAimRerollTicks() {
        double sample = AIM_REROLL_MEAN_TICKS + RANDOM.nextGaussian() * AIM_REROLL_STDDEV_TICKS;
        long rounded = Math.round(sample);
        return (int) Math.max(AIM_REROLL_MIN_TICKS, Math.min(AIM_REROLL_MAX_TICKS, rounded));
    }

    private static void maybeRerollAimOffset(Entity target) {
        if (aimOffsetTarget == target && --ticksUntilAimReroll > 0) return;
        AABB box = target.getBoundingBox();
        double width = box.maxX - box.minX;
        double height = box.maxY - box.minY;
        aimOffsetX = (RANDOM.nextDouble() - 0.5) * width * 0.6;
        aimOffsetZ = (RANDOM.nextDouble() - 0.5) * width * 0.6;
        aimOffsetY = height * (0.55 + RANDOM.nextDouble() * 0.35); // mostly upper-body/head, not always dead center
        aimOffsetTarget = target;
        ticksUntilAimReroll = sampleAimRerollTicks();
    }

    private static void attackOnce(Entity target) {
        Minecraft mc = Minecraft.getInstance();
        RotationUtil.lookAtExact(mc.player, target);
        mc.gameMode.attack(mc.player, target);
    }

    private static final double ATTACK_REACH_SQ = 3.0 * 3.0; // a bit under vanilla's 4.5 melee reach so it swings comfortably in range, not right at the edge

    /**
     * Re-attacks on a bell-curve-distributed interval (mean 10 ticks) until the target dies -- not
     * real weapon-cooldown timing, just natural-feeling irregularity instead of a metronome. A
     * single persistent tick listener drives this (registered once, ever) rather than one per call:
     * the previous version registered a fresh END_CLIENT_TICK closure on every attackUntilDead call,
     * which leaked a listener forever once its target died (it just silently no-op'd on every future
     * tick instead of unregistering) and, worse, would run multiple overlapping attack loops at once
     * if called again before the first target died -- exactly what a repeated on-damage "defend"
     * event does. Calling this again just retargets the one shared ticker instead.
     *
     * "Kill/Kill All/Follow don't pursue the entity" -- this method never walked toward the target
     * at all before this fix; it only ever turned and swung, correct for GrindModeController's
     * stationary "sit and fight whatever wanders into view" use case, but wrong for a target that
     * runs. Now also starts BaritoneNav.followEntity so the bot actually closes the distance
     * (cancelled in stopAttacking() and the ticker's target-died branch below); the ticker itself
     * only swings once player.distanceToSqr(target) is actually within ATTACK_REACH_SQ, not on a
     * fixed timer regardless of range.
     */
    private static void attackUntilDead(Entity target) {
        attackTarget = target;
        attacking = true;
        ticksUntilNextAttack = 0;
        BaritoneNav.followEntity(target);
        ensureAttackTicker();
    }

    private static void ensureAttackTicker() {
        if (attackTickerRegistered) return;
        attackTickerRegistered = true;
        // An uncaught exception here crashes the whole client -- confirmed
        // live, see TODO.md and PushToTalk's tick handler. Never let one escape.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                Entity target = attackTarget;
                if (target == null) return;
                LocalPlayer player = client.player;
                if (!target.isAlive()) {
                    attackTarget = null;
                    attacking = false;
                    BaritoneNav.cancelFollow();
                    player.setSprinting(false);
                    restoreStrafeInput(player);
                    return;
                }

                maybeRerollAimOffset(target);
                RotationUtil.smoothLookAtHumanized(player,
                        target.getX() + aimOffsetX, target.getY() + aimOffsetY, target.getZ() + aimOffsetZ,
                        ATTACK_TURN_RATE);

                boolean inReach = player.distanceToSqr(target) <= ATTACK_REACH_SQ;

                // "Add strafing when we have ample food, running and jumping at the same time" --
                // only once actually in reach (juking mid-approach would just fight Baritone's own
                // walk) and only while Baritone itself isn't mid-path (BaritoneNav.isPathing()) --
                // real risk this still contends with Baritone's own movement control on some tick;
                // not verified live, see TODO.md.
                if (inReach && !BaritoneNav.isPathing() && player.getFoodData().getFoodLevel() >= FOOD_STRAFE_THRESHOLD) {
                    applyStrafe(player);
                } else {
                    restoreStrafeInput(player);
                }

                if (!inReach) {
                    return; // still closing in -- BaritoneNav is doing the walking; nothing to swing at yet
                }

                boolean sword = hasSwordEquipped(player);
                player.setSprinting(!sword); // sword -> stay off sprint so crits stay possible; no sword -> sprint for the knockback bonus

                if (sword && ticksUntilNextAttack == CRIT_JUMP_LEAD_TICKS && player.onGround()) {
                    player.jumpFromGround();
                }

                if (ticksUntilNextAttack-- <= 0) {
                    client.gameMode.attack(player, target);
                    int nextInterval = sampleAttackIntervalTicks();
                    ticksUntilNextAttack = nextInterval;
                }
            } catch (RuntimeException e) {
                System.err.println("[ardor] attackUntilDead tick failed: " + e);
                attackTarget = null;
                attacking = false;
            }
        });
    }

    /**
     * Same ClientInput-swap technique PathExecutor uses for programmatic movement (vanilla's own
     * ClientInput.tick() would otherwise recompute moveVector from live keyboard state and stomp a
     * direct write to the field before it takes effect -- confirmed via PathExecutor's own
     * disassembly-backed doc, not re-verified here) -- swapped in once per strafe session and
     * restored by restoreStrafeInput, not every tick. Pure sideways (Vec2 x-only): closing the
     * distance stays entirely Baritone's job, this only adds the lateral "juke." Jumping is scoped
     * to the no-sword case only, so it can't disturb the sword path's precisely-timed crit jump.
     */
    private static void applyStrafe(LocalPlayer player) {
        if (!strafeInputActive) {
            savedInputForStrafe = player.input;
            player.input = new ClientInput();
            strafeInputActive = true;
        }
        if (--ticksUntilStrafeFlip <= 0) {
            strafingRight = !strafingRight;
            ticksUntilStrafeFlip = STRAFE_FLIP_TICKS + RANDOM.nextInt(10);
        }
        player.input.moveVector = new Vec2(strafingRight ? 1f : -1f, 0f);
        if (!hasSwordEquipped(player) && player.onGround() && RANDOM.nextInt(STRAFE_JUMP_CHANCE_DENOM) == 0) {
            player.jumpFromGround();
        }
    }

    private static void restoreStrafeInput(LocalPlayer player) {
        if (strafeInputActive) {
            player.input = savedInputForStrafe;
            savedInputForStrafe = null;
            strafeInputActive = false;
        }
    }

    private static void handleDrop(JsonObject action) {
        JsonObject item = action.getAsJsonObject("item");
        boolean all = action.has("all") && action.get("all").getAsBoolean();
        LocalPlayer player = Minecraft.getInstance().player;
        Inventory inv = player.getInventory();
        Item target = resolveItem(item.get("id").getAsString());
        int count = all ? Integer.MAX_VALUE : (item.has("count") ? item.get("count").getAsInt() : 1);

        int slotIndex = inv.findSlotMatchingItem(new ItemStack(target));
        if (slotIndex < 0) throw new IllegalStateException("drop: " + item.get("id").getAsString() + " not found in inventory");
        ItemStack removed = inv.removeItem(slotIndex, count);
        player.drop(removed, false);
    }

    private static void handleUse(JsonObject action) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (action.has("heldItem")) {
            selectItemInHand(action.getAsJsonObject("heldItem").get("id").getAsString());
        }

        var targetEl = action.get("target");
        if (!targetEl.isJsonObject()) {
            Entity target = requireEntity(targetEl.getAsString(), "use");
            RotationUtil.lookAtExact(player, target);
            mc.gameMode.interact(player, target, new EntityHitResult(target), InteractionHand.MAIN_HAND);
            return;
        }

        if (!action.has("position")) {
            throw new IllegalArgumentException("use: block target requires a position (blockRef alone has no location)");
        }
        BlockPos pos = readPos(action.getAsJsonObject("position"));
        RotationUtil.lookAtExact(player, pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
    }

    private static void handleChat(JsonObject action) {
        String message = action.get("message").getAsString();
        LocalPlayer player = Minecraft.getInstance().player;
        if (action.has("whisperTo")) {
            Entity target = requireEntity(action.get("whisperTo").getAsString(), "chat");
            if (!(target instanceof Player targetPlayer)) {
                throw new IllegalStateException("chat: whisperTo target is not a player");
            }
            player.connection.sendCommand("msg " + targetPlayer.getGameProfile().name() + " " + message);
            return;
        }
        player.connection.sendChat(message);
    }

    /** `cmd "f home"` -- sends an arbitrary server/client command (no leading slash in the field; sendCommand adds the routing itself). Lets the bot use whatever a given server exposes (factions homes, warps, etc.) without needing a native verb for each one. */
    private static void handleCommand(JsonObject action) {
        String command = action.get("command").getAsString();
        if (command.startsWith("/")) command = command.substring(1);
        Minecraft.getInstance().player.connection.sendCommand(command);
    }

    /**
     * Finds any non-special CraftingRecipe whose output matches the requested item and repeatedly
     * consumes its ingredients straight out of Inventory to produce the result -- no real crafting
     * grid, table, or click simulation. useCraftingTable only gates a 3x3-shaped-recipe check; it
     * doesn't otherwise affect how crafting happens.
     */
    private static void handleCraft(JsonObject action) {
        JsonObject itemRef = action.getAsJsonObject("item");
        String itemId = itemRef.get("id").getAsString();
        Item targetItem = resolveItem(itemId);
        int wanted = itemRef.has("count") ? itemRef.get("count").getAsInt() : 1;
        boolean useCraftingTable = action.has("useCraftingTable") && action.get("useCraftingTable").getAsBoolean();

        RecipeHolder<CraftingRecipe> holder = findCraftingRecipe(targetItem);
        if (holder == null) {
            throw new IllegalStateException("craft: no recipe found producing " + itemId);
        }
        CraftingRecipe recipe = holder.value();
        if (!useCraftingTable && recipe instanceof ShapedRecipe shaped
                && (shaped.getWidth() > 2 || shaped.getHeight() > 2)) {
            throw new IllegalStateException("craft: " + itemId + " needs a crafting table (3x3 recipe)");
        }

        Inventory inv = Minecraft.getInstance().player.getInventory();
        Map<Ingredient, Integer> tally = new LinkedHashMap<>();
        for (Ingredient ingredient : recipe.placementInfo().ingredients()) {
            tally.merge(ingredient, 1, Integer::sum);
        }

        int resultCount = recipe.assemble(CraftingInput.EMPTY).getCount();
        int produced = 0;
        while (produced < wanted && tally.entrySet().stream().allMatch(e -> countMatching(inv, e.getKey()) >= e.getValue())) {
            tally.forEach((ingredient, needed) -> removeMatching(inv, ingredient, needed));
            inv.add(recipe.assemble(CraftingInput.EMPTY));
            produced += resultCount;
        }
        if (produced == 0) {
            throw new IllegalStateException("craft: not enough ingredients for " + itemId);
        }
    }

    /**
     * Finds the CraftingRecipe (shaped or shapeless) that produces targetItem, or null if none
     * exists -- extracted from handleCraft so RealCraftingController (real crafting-table
     * interaction, see its own class doc for why that exists) can reuse the same safe, probe-
     * skipping search rather than duplicating it. Probing every non-special recipe's
     * assemble(CraftingInput.EMPTY) just to check its output item is how "couldn't craft
     * minecraft:wooden_pickaxe: Index 1 out of bounds for length 0" happened live -- some OTHER
     * recipe threw when probed with a zero-slot input; skipping (not aborting) on a probe failure
     * means one bad recipe can't block finding a good one for a completely different target item
     * further down the list.
     */
    static RecipeHolder<CraftingRecipe> findCraftingRecipe(Item targetItem) {
        RecipeManager recipeManager = requireRecipeManager();
        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            if (!(holder.value() instanceof CraftingRecipe candidate) || candidate.isSpecial()) continue;
            ItemStack result;
            try {
                result = candidate.assemble(CraftingInput.EMPTY);
            } catch (RuntimeException e) {
                System.err.println("[ardor] craft: skipping recipe " + holder.id() + " (threw probing its own output): " + e);
                continue;
            }
            if (result.getItem() == targetItem) {
                return new RecipeHolder<>(holder.id(), candidate);
            }
        }
        return null;
    }

    /**
     * Looks up the SmeltingRecipe keyed by the given input item and instantly produces its result --
     * no furnace block, burn-value/fuel-duration math, or cook-time wait. If fuel is given, one unit
     * of it is consumed per input smelted (unconditionally, not modeled against real burn values);
     * omitting fuel consumes none.
     */
    private static void handleSmelt(JsonObject action) {
        JsonObject inputRef = action.getAsJsonObject("input");
        String inputId = inputRef.get("id").getAsString();
        Item inputItem = resolveItem(inputId);
        int wanted = inputRef.has("count") ? inputRef.get("count").getAsInt() : 1;

        RecipeManager recipeManager = requireRecipeManager();
        SingleRecipeInput input = new SingleRecipeInput(new ItemStack(inputItem));
        RecipeHolder<SmeltingRecipe> holder = recipeManager
                .getRecipeFor(RecipeType.SMELTING, input, Minecraft.getInstance().level)
                .orElseThrow(() -> new IllegalStateException("smelt: no smelting recipe for " + inputId));
        SmeltingRecipe recipe = holder.value();

        String fuelId = action.has("fuel") ? action.getAsJsonObject("fuel").get("id").getAsString() : null;
        Item fuelItem = fuelId != null ? resolveItem(fuelId) : null;

        Inventory inv = Minecraft.getInstance().player.getInventory();
        Predicate<ItemStack> inputMatch = stack -> !stack.isEmpty() && stack.getItem() == inputItem;
        Predicate<ItemStack> fuelMatch = fuelItem == null ? null : stack -> !stack.isEmpty() && stack.getItem() == fuelItem;

        int smelted = 0;
        while (smelted < wanted
                && countMatching(inv, inputMatch) >= 1
                && (fuelMatch == null || countMatching(inv, fuelMatch) >= 1)) {
            removeMatching(inv, inputMatch, 1);
            if (fuelMatch != null) removeMatching(inv, fuelMatch, 1);
            inv.add(recipe.assemble(input));
            smelted++;
        }
        if (smelted == 0) {
            throw new IllegalStateException("smelt: not enough " + inputId + (fuelId != null ? " or fuel " + fuelId : ""));
        }
    }

    /** craft/smelt need a full recipe list, which the client no longer holds itself in this MC version -- only the integrated server's RecipeManager has it, so this only works in singleplayer (see class javadoc). */
    private static RecipeManager requireRecipeManager() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            throw new IllegalStateException("craft/smelt requires a singleplayer world (no integrated server)");
        }
        return server.getRecipeManager();
    }

    private static int countMatching(Inventory inv, Predicate<ItemStack> match) {
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (match.test(stack)) total += stack.getCount();
        }
        return total;
    }

    private static void removeMatching(Inventory inv, Predicate<ItemStack> match, int amount) {
        for (int i = 0; i < inv.getContainerSize() && amount > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (match.test(stack)) {
                int take = Math.min(amount, stack.getCount());
                inv.removeItem(i, take);
                amount -= take;
            }
        }
    }

    private static BlockPos readPos(JsonObject pos) {
        return new BlockPos(
                (int) Math.floor(pos.get("x").getAsDouble()),
                (int) Math.floor(pos.get("y").getAsDouble()),
                (int) Math.floor(pos.get("z").getAsDouble())
        );
    }

    private static Entity requireEntity(String selector, String forAction) {
        Entity entity = SelectorResolver.resolveOne(selector);
        if (entity == null) throw new IllegalStateException(forAction + ": no entity matched " + selector);
        return entity;
    }
}
