package com.ardor.container;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads/writes a shulker box's or bundle's contents directly off the host ItemStack's data
 * components -- no GUI needed, same "no menu required" philosophy game.ContainerSearch.asContainer
 * already uses for physical containers, just a different accessor for stacks instead of block
 * entities.
 *
 * Verified via `javap` against the real 26.1.2 client jar (see TODO.md), not assumed --
 * DataComponents.CONTAINER holds a shulker box's contents as ItemContainerContents;
 * DataComponents.BUNDLE_CONTENTS holds a bundle's as BundleContents. Both changed shape across MC
 * versions historically, per TODO.md's own warning, so neither name/type was assumed from memory.
 */
public final class SubContainerAccess {

    private SubContainerAccess() {}

    public static boolean isShulkerBox(ItemStack stack) {
        return stack.has(DataComponents.CONTAINER);
    }

    public static boolean isBundle(ItemStack stack) {
        return stack.has(DataComponents.BUNDLE_CONTENTS);
    }

    public static boolean isSubContainer(ItemStack stack) {
        return isShulkerBox(stack) || isBundle(stack);
    }

    public static List<ItemStack> contentsOf(ItemStack host) {
        ItemContainerContents shulker = host.get(DataComponents.CONTAINER);
        if (shulker != null) return shulker.nonEmptyItemCopyStream().toList();
        BundleContents bundle = host.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) return bundle.itemCopyStream().toList();
        return List.of();
    }

    /**
     * Removes and returns the first item in host's contents matching itemId, rewriting host's own
     * contents component in place (mutates the passed ItemStack -- same "direct field write"
     * simplification game.ContainerSearch.takeMatching already accepts for the physical-container
     * write direction). Returns null if nothing matched. Caller is responsible for writing the
     * (now-mutated) host stack back into whatever slot it came from.
     */
    public static ItemStack takeMatching(ItemStack host, String itemId) {
        ItemContainerContents shulker = host.get(DataComponents.CONTAINER);
        if (shulker != null) return takeFromShulker(host, shulker, itemId);
        BundleContents bundle = host.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) return takeFromBundle(host, bundle, itemId);
        return null;
    }

    private static ItemStack takeFromShulker(ItemStack host, ItemContainerContents contents, String itemId) {
        List<ItemStack> items = new ArrayList<>(contents.nonEmptyItemCopyStream().toList());
        for (int i = 0; i < items.size(); i++) {
            if (matchesId(items.get(i), itemId)) {
                ItemStack taken = items.remove(i);
                host.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
                return taken;
            }
        }
        return null;
    }

    /**
     * Re-inserts a leftover remainder back into host's contents -- used by
     * container.ContainerFetchService's partial-take path (container.FetchQueue's multi-source
     * fulfillment loop only wants up to a remaining amount from any one source, and takeMatching
     * above always removes a whole matched stack, so the excess beyond what was wanted gets put
     * back here rather than lost). No-op for an empty stack.
     */
    public static void putBack(ItemStack host, ItemStack stack) {
        if (stack.isEmpty()) return;
        ItemContainerContents shulker = host.get(DataComponents.CONTAINER);
        if (shulker != null) {
            List<ItemStack> items = new ArrayList<>(shulker.nonEmptyItemCopyStream().toList());
            items.add(stack);
            host.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
            return;
        }
        BundleContents bundle = host.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            List<ItemStackTemplate> templates = new ArrayList<>(bundle.items());
            templates.add(ItemStackTemplate.fromNonEmptyStack(stack));
            host.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(templates));
        }
    }

    private static ItemStack takeFromBundle(ItemStack host, BundleContents contents, String itemId) {
        List<ItemStackTemplate> templates = new ArrayList<>(contents.items());
        for (int i = 0; i < templates.size(); i++) {
            ItemStack candidate = templates.get(i).create();
            if (matchesId(candidate, itemId)) {
                templates.remove(i);
                host.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(templates));
                return candidate;
            }
        }
        return null;
    }

    private static boolean matchesId(ItemStack stack, String itemId) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && id.toString().equals(itemId);
    }
}
