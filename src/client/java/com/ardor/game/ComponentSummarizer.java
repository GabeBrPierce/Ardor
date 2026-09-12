package com.ardor.game;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.equipment.trim.ArmorTrim;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a live ItemStack's data components into human-readable summary
 * lines. Ported to MC 26.1.2's unobfuscated names, replacing the earlier
 * Yarn version -- same role: runs client-side at record time, writes into
 * the IR's itemStack.componentSummary field, and nothing downstream needs to
 * understand raw component data.
 *
 * CONFIRMED via direct javap disassembly of the actual MC 26.1.2 jar (from
 * the Gradle build cache, not a downloaded reference copy): Minecraft.
 * getInstance(), DataComponents as the holder of DataComponentType constants,
 * ItemStack.get(DataComponentType)/.has(DataComponentType), and -- fixed
 * after the first real build caught them wrong -- Holder<T> has no getKey():
 * the correct chain is Holder.unwrapKey().orElseThrow().identifier(), and
 * ArmorTrim lives in net.minecraft.world.item.equipment.trim (matching
 * where the Yarn version's own follow-up verification pass had put it,
 * ironically) with pattern().value().copyWithStyle(material()) producing the
 * combined description Component directly.
 *
 * NOT independently confirmed -- ItemEnchantments, ItemLore,
 * ItemAttributeModifiers, FoodProperties, and PotionContents' exact method
 * shapes are still written from general knowledge of Mojang's mapping
 * conventions rather than checked against this jar; they didn't happen to
 * throw a compile error, which is weaker evidence than an error that didn't
 * happen. Worth a real runtime test (an enchanted, named item with lore)
 * before trusting the output, not just a clean compile.
 */
public final class ComponentSummarizer {

    private ComponentSummarizer() {}

    public static List<String> summarize(ItemStack stack) {
        List<String> lines = new ArrayList<>();

        ItemEnchantments enchants = stack.get(DataComponents.ENCHANTMENTS);
        if (enchants != null && !enchants.isEmpty()) {
            enchants.keySet().forEach(enchantment ->
                    lines.add(describeEnchantment(enchantment, enchants.getLevel(enchantment))));
        }

        Component customName = stack.get(DataComponents.CUSTOM_NAME);
        if (customName != null) {
            lines.add("named \"" + customName.getString() + "\"");
        }

        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) {
            for (Component line : lore.lines()) lines.add(line.getString());
        }

        if (stack.has(DataComponents.UNBREAKABLE)) {
            lines.add("unbreakable");
        }

        // Two stacks of the same item at different durability must not collapse into one
        // aggregated group in the Fetch Items grid -- container.CacheSearch.groupedSearch groups
        // purely by (itemId, this summary), so durability has to show up here to be a differentiator.
        if (stack.isDamaged()) {
            lines.add("durability " + (stack.getMaxDamage() - stack.getDamageValue()) + "/" + stack.getMaxDamage());
        }

        ItemAttributeModifiers attrs = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (attrs != null) {
            for (ItemAttributeModifiers.Entry entry : attrs.modifiers()) {
                lines.add(describeAttributeModifier(entry));
            }
        }

        ArmorTrim trim = stack.get(DataComponents.TRIM);
        if (trim != null) {
            lines.add(trim.pattern().value().copyWithStyle(trim.material()).getString());
        }

        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food != null) {
            String line = food.nutrition() + " hunger";
            if (food.saturation() > 0) line += String.format(", %.1f saturation", food.saturation());
            if (food.canAlwaysEat()) line += " (edible even when full)";
            lines.add(line);
        }

        PotionContents potion = stack.get(DataComponents.POTION_CONTENTS);
        if (potion != null) {
            potion.getAllEffects().forEach(effect -> lines.add(describeStatusEffect(effect)));
        }

        return lines;
    }

    private static String describeEnchantment(Holder<Enchantment> enchantment, int level) {
        Identifier id = enchantment.unwrapKey().orElseThrow().identifier();
        String name = Component.translatable("enchantment." + id.getNamespace() + "." + id.getPath()).getString();
        if (level <= 1) return name;
        return name + " " + Component.translatable("enchantment.level." + level).getString();
    }

    private static String describeAttributeModifier(ItemAttributeModifiers.Entry entry) {
        Holder<Attribute> attribute = entry.attribute();
        Identifier id = attribute.unwrapKey().orElseThrow().identifier();
        String attrName = Component.translatable("attribute.name." + id.getNamespace() + "." + id.getPath()).getString();
        double amount = entry.modifier().amount();
        String sign = amount >= 0 ? "+" : "";
        return sign + trimNum(amount) + " " + attrName;
    }

    private static String describeStatusEffect(MobEffectInstance effect) {
        Holder<MobEffect> type = effect.getEffect();
        Identifier id = type.unwrapKey().orElseThrow().identifier();
        String name = Component.translatable("effect." + id.getNamespace() + "." + id.getPath()).getString();
        int amplifier = effect.getAmplifier();
        if (amplifier > 0) {
            name += " " + Component.translatable("enchantment.level." + (amplifier + 1)).getString();
        }
        return name;
    }

    private static String trimNum(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.valueOf(v);
    }
}
