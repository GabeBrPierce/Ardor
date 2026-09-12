package com.ardor.game;

import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Consumer;

/**
 * "We should actually look at a crafting table and craft using the UI there instead of doing it
 * seemingly by magic." Confirmed live: GameActionController.handleCraft's direct
 * `inv.add(recipe.assemble(...))` produces a "ghost" item -- visible client-side the instant it's
 * called, but the integrated server's own authoritative Inventory was never told, so the item
 * vanishes the moment any real server round-trip corrects the client back to truth (even in
 * singleplayer -- "same process" was never "same object graph"; the client and integrated server
 * are still two separate Player/Inventory instances kept in sync by the same packet protocol a
 * real remote server uses, just over an in-memory pipe instead of a socket).
 *
 * This crafts via REAL slot-click packets against an ACTUALLY opened crafting table's menu
 * instead: MultiPlayerGameMode.handleContainerInput(containerId, slotId, button, ContainerInput,
 * player) is the real client-side entry point a vanilla Screen's own slot click calls (confirmed
 * via javap disassembly of its body: it validates containerId against player.containerMenu, then
 * calls menu.clicked(slotId, button, input, player) directly -- the SAME shared client/server
 * logic a real click runs, immediately mutating real Slots and separately queuing the packet for
 * the server to independently verify/apply). Moving exactly one item from an inventory slot into
 * a grid slot is a real 3-click sequence (pick up the whole source stack onto the cursor, right-
 * click the target to place exactly one, left-click the source again to put the remainder back)
 * -- not a shortcut, the literal thing a careful player does by hand.
 *
 * Scoped to ONLY table-requiring (width/height > 2x2) recipes for now -- exactly the two call
 * sites that actually reported this bug (craftToolFromScratch's pickaxe, ensureChests' chest).
 * ensurePlanks/ensureSticks (2x2 recipes) still use the old instant-simulate method -- same
 * theoretical desync risk, not yet fixed, see TODO.md.
 *
 * This is genuinely the least-provable-without-a-live-test piece of code in this whole project so
 * far: multi-tick menu-open polling, real click sequencing, and cleanup all had to be reasoned
 * out from `javap` disassembly rather than exercised against a running game. Treat a first use of
 * this with real caution.
 */
public final class RealCraftingController {

    private static final int TABLE_SEARCH_RADIUS = 16;
    private static final int PLACEMENT_SEARCH_RADIUS = 6;
    private static final int MENU_OPEN_TIMEOUT_TICKS = 60; // 3s -- generous for a local round trip

    private RealCraftingController() {}

    /**
     * Crafts `wanted` of itemId using a real nearby-or-obtained crafting table -- finds one within
     * TABLE_SEARCH_RADIUS first; if none exists, ensures a crafting_table item (crafting one from
     * planks via the OLD instant method is accepted here -- a single cheap, immediately-placed
     * item is a much smaller desync surface than the tool/chest itself sitting in inventory) and
     * places it nearby. onReady fires once `wanted` items have actually been crafted and are (as
     * far as this can tell) really in the inventory; onFailed(reason) fires and the table's menu
     * (if one was opened) is closed on any failure, so this doesn't leave the player stuck staring
     * at an open screen.
     */
    public static void craft(String itemId, int wanted, Runnable onReady, Consumer<String> onFailed) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null) {
            onFailed.accept("no client player/level loaded");
            return;
        }

        Item targetItem = BuiltInRegistries.ITEM.getOptional(Identifier.parse(itemId)).orElse(null);
        if (targetItem == null) {
            onFailed.accept("unknown item id: " + itemId);
            return;
        }
        RecipeHolder<CraftingRecipe> holder = GameActionController.findCraftingRecipe(targetItem);
        if (holder == null) {
            onFailed.accept("craft: no recipe found producing " + itemId);
            return;
        }

        ensureNearbyCraftingTable(tablePos ->
                        openAndCraft(tablePos, holder, wanted, onReady, onFailed),
                onFailed);
    }

    // ------------------------------------------------------- finding/obtaining a real table

    private static void ensureNearbyCraftingTable(Consumer<BlockPos> onReady, Consumer<String> onFailed) {
        Level level = Minecraft.getInstance().level;
        LocalPlayer player = Minecraft.getInstance().player;
        BlockPos existing = findNearbyTable(level, player.blockPosition());
        if (existing != null) {
            onReady.accept(existing);
            return;
        }

        if (countCraftingTables(player) > 0) {
            placeTableNearby(onReady, onFailed);
            return;
        }

        // No table anywhere in reach and none in inventory -- craft one (4 planks, a 2x2 recipe,
        // via the old instant method: a single cheap, immediately-placed item, a much smaller
        // desync surface than what this whole class exists to fix for the tool/chest itself).
        PathfindingController.ensurePlanksPublic(4, () -> {
            try {
                GameActionController.dispatch(craftInstantAction("minecraft:crafting_table", 1));
            } catch (RuntimeException e) {
                onFailed.accept("couldn't craft a crafting_table: " + e);
                return;
            }
            if (countCraftingTables(player) < 1) {
                onFailed.accept("crafted a crafting_table but it's not in the inventory afterward");
                return;
            }
            placeTableNearby(onReady, onFailed);
        });
    }

    private static int countCraftingTables(LocalPlayer player) {
        int count = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem() == net.minecraft.world.item.Items.CRAFTING_TABLE) count += stack.getCount();
        }
        return count;
    }

    private static BlockPos findNearbyTable(Level level, BlockPos center) {
        for (BlockPos pos : BlockPos.withinManhattan(center, TABLE_SEARCH_RADIUS, TABLE_SEARCH_RADIUS, TABLE_SEARCH_RADIUS)) {
            if (level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) return pos.immutable();
        }
        return null;
    }

    private static void placeTableNearby(Consumer<BlockPos> onReady, Consumer<String> onFailed) {
        LocalPlayer player = Minecraft.getInstance().player;
        BlockPos spot = findPlacementSpot(Minecraft.getInstance().level, player.blockPosition());
        if (spot == null) {
            onFailed.accept("couldn't find a clear spot to place a crafting table nearby");
            return;
        }
        PathfindingController.walkThenRun(spot, "couldn't reach " + spot + " to place a crafting table",
                () -> {
                    placeTableAt(spot);
                    onReady.accept(spot);
                },
                failReason -> onFailed.accept("couldn't reach a spot to place a crafting table: " + failReason));
    }

    private static void placeTableAt(BlockPos pos) {
        var action = new com.google.gson.JsonObject();
        action.addProperty("action", "place");
        var block = new com.google.gson.JsonObject();
        block.addProperty("id", "minecraft:crafting_table");
        action.add("block", block);
        var position = new com.google.gson.JsonObject();
        position.addProperty("x", pos.getX());
        position.addProperty("y", pos.getY());
        position.addProperty("z", pos.getZ());
        action.add("position", position);
        action.addProperty("facing", "up");
        GameActionController.dispatch(action);
    }

    private static BlockPos findPlacementSpot(Level level, BlockPos near) {
        for (BlockPos pos : BlockPos.withinManhattan(near, PLACEMENT_SEARCH_RADIUS, 2, PLACEMENT_SEARCH_RADIUS)) {
            if (level.getBlockState(pos).isAir()
                    && level.getBlockState(pos.above()).isAir()
                    && !level.getBlockState(pos.below()).isAir()) {
                return pos.immutable();
            }
        }
        return null;
    }

    private static com.google.gson.JsonObject craftInstantAction(String itemId, int count) {
        var action = new com.google.gson.JsonObject();
        action.addProperty("action", "craft");
        var item = new com.google.gson.JsonObject();
        item.addProperty("id", itemId);
        item.addProperty("count", count);
        action.add("item", item);
        return action;
    }

    // ------------------------------------------------------- opening + real click sequence

    private static void openAndCraft(BlockPos tablePos, RecipeHolder<CraftingRecipe> holder, int wanted, Runnable onReady, Consumer<String> onFailed) {
        PathfindingController.walkThenRun(tablePos, "couldn't reach the crafting table at " + tablePos,
                () -> interactAndWaitForMenu(tablePos, holder, wanted, onReady, onFailed),
                failReason -> onFailed.accept("couldn't reach the crafting table: " + failReason));
    }

    private static void interactAndWaitForMenu(BlockPos tablePos, RecipeHolder<CraftingRecipe> holder, int wanted, Runnable onReady, Consumer<String> onFailed) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (!level.getBlockState(tablePos).is(Blocks.CRAFTING_TABLE)) {
            onFailed.accept("the crafting table at " + tablePos + " is gone");
            return;
        }

        RotationUtil.lookAtExact(player, tablePos);
        Direction face = Direction.UP;
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(tablePos), face, tablePos, false);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);

        pollForMenuOpen(0, holder, wanted, onReady, onFailed);
    }

    /**
     * Opening a menu is itself a full client-server round trip (the server assigns a new
     * containerId and sends a ClientboundOpenScreenPacket back) -- not synchronous even in
     * singleplayer, so this polls player.containerMenu across ticks rather than assuming it's
     * already a CraftingMenu the instant useItemOn returns.
     */
    private static void pollForMenuOpen(int ticksWaited, RecipeHolder<CraftingRecipe> holder, int wanted, Runnable onReady, Consumer<String> onFailed) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player.containerMenu instanceof CraftingMenu menu) {
            runCraftLoop(menu, holder, wanted, 0, onReady, onFailed);
            return;
        }
        if (ticksWaited >= MENU_OPEN_TIMEOUT_TICKS) {
            onFailed.accept("crafting table never opened a menu (timed out)");
            return;
        }
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(new OneShotPoll(ticksWaited, holder, wanted, onReady, onFailed));
    }

    /** A single-fire tick listener (Fabric's event API has no unregister, so this just checks a "done" flag on every remaining tick instead of ever re-registering itself). */
    private static final class OneShotPoll implements net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.EndTick {
        private final int ticksWaited;
        private final RecipeHolder<CraftingRecipe> holder;
        private final int wanted;
        private final Runnable onReady;
        private final Consumer<String> onFailed;
        private boolean done;

        OneShotPoll(int ticksWaited, RecipeHolder<CraftingRecipe> holder, int wanted, Runnable onReady, Consumer<String> onFailed) {
            this.ticksWaited = ticksWaited;
            this.holder = holder;
            this.wanted = wanted;
            this.onReady = onReady;
            this.onFailed = onFailed;
        }

        @Override
        public void onEndTick(Minecraft client) {
            if (done) return;
            done = true;
            try {
                pollForMenuOpen(ticksWaited + 1, holder, wanted, onReady, onFailed);
            } catch (RuntimeException e) {
                System.err.println("[ardor] real craft menu-open poll failed: " + e);
                onFailed.accept("menu-open poll failed: " + e);
            }
        }
    }

    private static void runCraftLoop(CraftingMenu menu, RecipeHolder<CraftingRecipe> holder, int wanted, int produced, Runnable onReady, Consumer<String> onFailed) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (produced >= wanted) {
            player.closeContainer();
            onReady.run();
            return;
        }

        CraftingRecipe recipe = holder.value();
        if (!fillGrid(menu, player, recipe)) {
            player.closeContainer();
            onFailed.accept("couldn't fill the crafting grid -- missing an ingredient in inventory");
            return;
        }

        int resultCount = recipe.assemble(CraftingInput.EMPTY).getCount();
        Slot resultSlot = menu.getResultSlot();
        if (!resultSlot.hasItem()) {
            player.closeContainer();
            onFailed.accept("filled the grid but the table doesn't recognize the recipe");
            return;
        }
        Minecraft.getInstance().gameMode.handleContainerInput(menu.containerId, resultSlot.index, 0, ContainerInput.QUICK_MOVE, player);

        runCraftLoop(menu, holder, wanted, produced + resultCount, onReady, onFailed);
    }

    /** Row-major, top-left-anchored placement of the recipe's ingredients into the table's real 3x3 grid -- PlacementInfo is generic over Shaped/Shapeless (both are CraftingRecipe), so this doesn't special-case either. */
    private static boolean fillGrid(CraftingMenu menu, LocalPlayer player, CraftingRecipe recipe) {
        PlacementInfo placement = recipe.placementInfo();
        List<Ingredient> ingredients = placement.ingredients();
        IntList slotMap = placement.slotsToIngredientIndex();
        int width = (recipe instanceof ShapedRecipe shaped) ? shaped.getWidth() : slotMap.size();

        List<Slot> gridSlots = menu.getInputGridSlots();
        int gridWidth = gridSlots.size() == 4 ? 2 : 3;

        for (int i = 0; i < slotMap.size(); i++) {
            int ingredientIndex = slotMap.getInt(i);
            if (ingredientIndex == PlacementInfo.EMPTY_SLOT) continue;
            int row = i / width, col = i % width;
            if (row >= gridWidth || col >= gridWidth) return false; // recipe too big for this grid
            Slot targetSlot = gridSlots.get(row * gridWidth + col);
            if (targetSlot.hasItem()) continue; // already filled from a previous craft in this same loop iteration's leftovers
            if (!moveOneMatchingIntoSlot(menu, player, ingredients.get(ingredientIndex), targetSlot.index)) return false;
        }
        return true;
    }

    /**
     * The real 3-click sequence a careful player does by hand: pick up the whole source stack
     * (button 0 = left click, PICKUP) onto the cursor, right-click (button 1) the target slot to
     * place exactly ONE item there, then left-click the source slot again to put the remainder
     * back. Not a shortcut -- this is genuinely how "move one item, not the whole stack" works via
     * real container clicks.
     */
    private static boolean moveOneMatchingIntoSlot(AbstractContainerMenu menu, LocalPlayer player, Ingredient ingredient, int targetSlotIndex) {
        int sourceSlotIndex = -1;
        for (Slot slot : menu.slots) {
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && slot.index != targetSlotIndex && ingredient.test(stack)) {
                sourceSlotIndex = slot.index;
                break;
            }
        }
        if (sourceSlotIndex < 0) return false;

        var mc = Minecraft.getInstance();
        int containerId = menu.containerId;
        mc.gameMode.handleContainerInput(containerId, sourceSlotIndex, 0, ContainerInput.PICKUP, player);
        mc.gameMode.handleContainerInput(containerId, targetSlotIndex, 1, ContainerInput.PICKUP, player);
        mc.gameMode.handleContainerInput(containerId, sourceSlotIndex, 0, ContainerInput.PICKUP, player);
        return true;
    }
}
