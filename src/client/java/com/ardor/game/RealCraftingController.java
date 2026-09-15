package com.ardor.game;

import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Crafts via real slot-click packets against an actually-opened crafting screen (CraftingScreen
 * for a table, InventoryScreen for the player's own 2x2 grid), paced CLICK_PACE_TICKS apart so the
 * sequence is watchable instead of instant. Recipes that fit 2x2 skip the table search entirely.
 */
public final class RealCraftingController {

    private static final int TABLE_SEARCH_RADIUS = 16;
    private static final int PLACEMENT_SEARCH_RADIUS = 6;
    private static final int MENU_OPEN_TIMEOUT_TICKS = 60; // 3s -- generous for a local round trip
    private static final int CLICK_PACE_TICKS = 6; // ~0.3s at 20tps between each real click -- slow enough to watch, not so slow it feels broken

    private RealCraftingController() {}

    /** Crafts `wanted` of itemId via real container clicks; onReady fires once crafted, onFailed(reason) fires (and any opened menu/screen is closed) on failure. */
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

        if (fitsIn2x2(holder.value())) {
            craftUsingInventoryGrid(holder, wanted, onReady, onFailed);
        } else {
            ensureNearbyCraftingTable(tablePos ->
                            openAndCraft(tablePos, holder, wanted, onReady, onFailed),
                    onFailed);
        }
    }

    /** Shaped recipes carry their own width/height; a shapeless recipe has no shape, just a bag of ingredients -- it fits a 2x2 grid iff it needs 4 or fewer distinct placements. */
    private static boolean fitsIn2x2(CraftingRecipe recipe) {
        if (recipe instanceof ShapedRecipe shaped) {
            return shaped.getWidth() <= 2 && shaped.getHeight() <= 2;
        }
        return recipe.placementInfo().ingredients().size() <= 4;
    }

    // ------------------------------------------------------- 2x2: the player's own inventory grid

    private static void craftUsingInventoryGrid(RecipeHolder<CraftingRecipe> holder, int wanted, Runnable onReady, Consumer<String> onFailed) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        InventoryMenu menu = player.inventoryMenu;
        mc.setScreen(new InventoryScreen(player));
        runCraftLoop(menu, holder, wanted, 0, () -> mc.setScreen(null), onReady, onFailed);
    }

    // ------------------------------------------------------- 3x3: finding/obtaining a real table

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

        // No table anywhere in reach and none in inventory -- craft one via the same real-click
        // path as everything else (crafting_table is a 2x2 recipe, so this recurses into
        // craftUsingInventoryGrid above, never back into this method).
        PathfindingController.ensurePlanksPublic(4, () ->
                craft("minecraft:crafting_table", 1,
                        () -> {
                            if (countCraftingTables(player) < 1) {
                                onFailed.accept("crafted a crafting_table but it's not in the inventory afterward");
                                return;
                            }
                            placeTableNearby(onReady, onFailed);
                        },
                        reason -> onFailed.accept("couldn't craft a crafting_table: " + reason)));
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

        pollForMenuOpen(holder, wanted, onReady, onFailed);
    }

    /** Opening a menu is a real client-server round trip, so this polls player.containerMenu across ticks rather than assuming it's ready the instant useItemOn returns. */
    private static void pollForMenuOpen(RecipeHolder<CraftingRecipe> holder, int wanted, Runnable onReady, Consumer<String> onFailed) {
        TickPoll.until(MENU_OPEN_TIMEOUT_TICKS,
                () -> Minecraft.getInstance().player.containerMenu instanceof CraftingMenu,
                () -> {
                    Minecraft mc = Minecraft.getInstance();
                    LocalPlayer player = mc.player;
                    CraftingMenu menu = (CraftingMenu) player.containerMenu;
                    mc.setScreen(new CraftingScreen(menu, player.getInventory(), Component.translatable("container.crafting")));
                    runCraftLoop(menu, holder, wanted, 0,
                            () -> { player.closeContainer(); mc.setScreen(null); },
                            onReady, onFailed);
                },
                () -> onFailed.accept("crafting table never opened a menu (timed out)"));
    }

    /** Fires `action` CLICK_PACE_TICKS ticks from now -- the pacing primitive every real click in this class goes through. */
    private static void afterDelay(int ticks, Runnable action) {
        TickPoll.after(ticks, action);
    }

    private static void runCraftLoop(AbstractCraftingMenu menu, RecipeHolder<CraftingRecipe> holder, int wanted, int produced,
                                      Runnable onClose, Runnable onReady, Consumer<String> onFailed) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (produced >= wanted) {
            onClose.run();
            onReady.run();
            return;
        }

        CraftingRecipe recipe = holder.value();
        fillGrid(menu, player, recipe, () -> {
            Slot resultSlot = menu.getResultSlot();
            if (!resultSlot.hasItem()) {
                onClose.run();
                onFailed.accept("filled the grid but the table doesn't recognize the recipe");
                return;
            }
            int resultCount = recipe.assemble(CraftingInput.EMPTY).getCount();
            afterDelay(CLICK_PACE_TICKS, () -> {
                Minecraft.getInstance().gameMode.handleContainerInput(menu.containerId, resultSlot.index, 0, ContainerInput.QUICK_MOVE, player);
                afterDelay(CLICK_PACE_TICKS, () -> runCraftLoop(menu, holder, wanted, produced + resultCount, onClose, onReady, onFailed));
            });
        }, reason -> {
            onClose.run();
            onFailed.accept(reason);
        });
    }

    /** Row-major, top-left-anchored placement of the recipe's ingredients into the real grid. Moves are collected first, then run one at a time via fillGridStep -- each move's slot-scan needs the previous move's click to have landed first. */
    private static void fillGrid(AbstractCraftingMenu menu, LocalPlayer player, CraftingRecipe recipe, Runnable onFilled, Consumer<String> onFailed) {
        PlacementInfo placement = recipe.placementInfo();
        List<Ingredient> ingredients = placement.ingredients();
        IntList slotMap = placement.slotsToIngredientIndex();
        int width = (recipe instanceof ShapedRecipe shaped) ? shaped.getWidth() : slotMap.size();

        List<Slot> gridSlots = menu.getInputGridSlots();
        int gridWidth = gridSlots.size() == 4 ? 2 : 3;

        List<int[]> moves = new ArrayList<>(); // {ingredientIndex, targetSlotIndex}
        for (int i = 0; i < slotMap.size(); i++) {
            int ingredientIndex = slotMap.getInt(i);
            if (ingredientIndex == PlacementInfo.EMPTY_SLOT) continue;
            int row = i / width, col = i % width;
            if (row >= gridWidth || col >= gridWidth) {
                onFailed.accept("recipe too big for this grid");
                return;
            }
            Slot targetSlot = gridSlots.get(row * gridWidth + col);
            if (targetSlot.hasItem()) continue; // already filled from a previous craft in this same loop iteration's leftovers
            moves.add(new int[]{ingredientIndex, targetSlot.index});
        }
        fillGridStep(menu, player, ingredients, moves, 0, onFilled, onFailed);
    }

    private static void fillGridStep(AbstractCraftingMenu menu, LocalPlayer player, List<Ingredient> ingredients, List<int[]> moves, int i,
                                      Runnable onFilled, Consumer<String> onFailed) {
        if (i >= moves.size()) {
            onFilled.run();
            return;
        }
        int[] move = moves.get(i);
        moveOneMatchingIntoSlot(menu, player, ingredients.get(move[0]), move[1],
                () -> fillGridStep(menu, player, ingredients, moves, i + 1, onFilled, onFailed),
                onFailed);
    }

    /** Real 3-click sequence, paced: pick up the whole source stack (button 0, PICKUP), right-click (button 1) the target to place exactly one, left-click the source again to put the remainder back. */
    private static void moveOneMatchingIntoSlot(AbstractContainerMenu menu, LocalPlayer player, Ingredient ingredient, int targetSlotIndex,
                                                 Runnable onDone, Consumer<String> onFailed) {
        int sourceSlotIndex = -1;
        for (Slot slot : menu.slots) {
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && slot.index != targetSlotIndex && ingredient.test(stack)) {
                sourceSlotIndex = slot.index;
                break;
            }
        }
        if (sourceSlotIndex < 0) {
            onFailed.accept("missing an ingredient in inventory");
            return;
        }

        int source = sourceSlotIndex;
        int containerId = menu.containerId;
        Minecraft mc = Minecraft.getInstance();
        afterDelay(CLICK_PACE_TICKS, () -> {
            mc.gameMode.handleContainerInput(containerId, source, 0, ContainerInput.PICKUP, player);
            afterDelay(CLICK_PACE_TICKS, () -> {
                mc.gameMode.handleContainerInput(containerId, targetSlotIndex, 1, ContainerInput.PICKUP, player);
                afterDelay(CLICK_PACE_TICKS, () -> {
                    mc.gameMode.handleContainerInput(containerId, source, 0, ContainerInput.PICKUP, player);
                    afterDelay(CLICK_PACE_TICKS, onDone);
                });
            });
        });
    }
}
