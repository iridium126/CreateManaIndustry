package com.iridium126.createmanaindustry.content.items;

import com.iridium126.createmanaindustry.CMIComponents;
import com.iridium126.createmanaindustry.content.recipes.KnotFillingLogic;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * An incomplete knot item that displays a mana-blue progress bar indicating
 * how much liquid mana has been infused during the multi-step Spout filling
 * process.
 */
public class IncompleteKnotItem extends Item {

    public IncompleteKnotItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return true;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        float creationCost = KnotFillingLogic.getCreationCost(stack);
        if (creationCost <= 0)
            return 0;
        return Math.round(getFilledMana(stack) / creationCost * 13);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return 0x3de0f7; // mana light blue
    }

    private static float getFilledMana(ItemStack stack) {
        Float value = stack.get(CMIComponents.FILLED_MANA.get());
        return value != null ? value : 0f;
    }
}
