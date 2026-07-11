package com.iridium126.createmanaindustry.content.items;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class KineticsSpellCoreItem {
    private static final ResourceLocation ID = CreateManaIndustry.modLoc("kinetics_spell_core");
    private static final String TRICKSTER_SPELL_CORE_CLASS = "dev.enjarai.trickster.item.SpellCoreItem";

    private KineticsSpellCoreItem() {}

    public static Item create(Item.Properties properties) {
        try {
            Object item = Class.forName(TRICKSTER_SPELL_CORE_CLASS)
                .getConstructor()
                .newInstance();
            if (item instanceof Item spellCoreItem)
                return spellCoreItem;
        } catch (Throwable t) {
            CreateManaIndustry.LOGGER.warn("Failed to create Trickster spell core item", t);
        }
        return new Item(properties.stacksTo(4));
    }

    public static boolean is(ItemStack stack) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(ID);
    }
}
