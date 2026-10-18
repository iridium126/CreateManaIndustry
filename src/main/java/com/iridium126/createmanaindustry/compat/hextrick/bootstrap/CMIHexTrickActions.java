package com.iridium126.createmanaindustry.compat.hextrick.bootstrap;

import at.petrak.hexcasting.api.casting.ActionRegistryEntry;
import at.petrak.hexcasting.api.casting.math.HexDir;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.common.lib.HexRegistries;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.compat.hextrick.conversion.OpMediaToMana;
import com.iridium126.createmanaindustry.compat.hextrick.execution.OpReadTrickFromItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registry for CMI's Trickster-related Hexcasting actions.
 * <p>
 * Deliberately separate from {@link CMIHexActions}: DeferredRegister
 * suppliers are evaluated eagerly during class initialisation, so an entry
 * referencing {@link OpReadTrickFromItem} (which loads Trickster classes)
 * must only live in a class that is itself loaded under the
 * hexcasting &&amp; trickster gate.
 */
public class CMIHexTrickActions {

    private static final DeferredRegister<ActionRegistryEntry> ACTIONS =
            DeferredRegister.create(HexRegistries.ACTION, CreateManaIndustry.MODID);

    public static final DeferredHolder<ActionRegistryEntry, ActionRegistryEntry> READ_TRICK_FROM_ITEM =
            ACTIONS.register("read_trick_from_item", () ->
                    new ActionRegistryEntry(
                            HexPattern.fromAngleString("qqqqqa", HexDir.NORTH_WEST),
                            OpReadTrickFromItem.INSTANCE));

    public static final DeferredHolder<ActionRegistryEntry, ActionRegistryEntry> MEDIA_TO_MANA =
            ACTIONS.register("media_to_mana", () -> new ActionRegistryEntry(
                    HexPattern.fromAngleString("dadaw", HexDir.NORTH_EAST), OpMediaToMana.INSTANCE));

    public static void register(IEventBus modEventBus) {
        ACTIONS.register(modEventBus);
    }
}
