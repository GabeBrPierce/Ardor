package com.ardor.container;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real creative-mode-tab groupings, for the Add New modal's "Categories" tab. Verified via
 * `javap` against the real 26.1.2 client jar (see TODO.md): CreativeModeTabs.tabs() returns the
 * real, already-populated CreativeModeTab list (BUILDING_BLOCKS, NATURAL_BLOCKS,
 * TOOLS_AND_UTILITIES, COMBAT, FOOD_AND_DRINKS, ... -- the actual vanilla category set, not a
 * reimplementation of it), and CreativeModeTab.getDisplayItems() gives each tab's real item list.
 */
public final class CreativeCategories {

    private CreativeCategories() {}

    /** Display name -> item ids in that creative tab. Computed on demand (not cached) -- CreativeModeTabs.tabs() reflects whatever's actually registered, including anything a datapack adds. */
    public static Map<String, List<String>> categories() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (CreativeModeTab tab : CreativeModeTabs.tabs()) {
            String name = tab.getDisplayName().getString();
            List<String> ids = tab.getDisplayItems().stream()
                    .map(CreativeCategories::idOf)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
            if (!ids.isEmpty()) result.put(name, ids);
        }
        return result;
    }

    private static String idOf(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null ? id.toString() : null;
    }
}
