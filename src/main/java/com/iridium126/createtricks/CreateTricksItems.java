package com.iridium126.createtricks;

import static com.iridium126.createtricks.CreateTricks.REGISTRATE;

import com.iridium126.createtricks.content.items.KineticsSpellCoreItem;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyItem;
import com.tterrag.registrate.providers.RegistrateRecipeProvider;
import com.tterrag.registrate.util.entry.ItemEntry;
import com.tterrag.registrate.util.nullness.NonNullBiConsumer;

import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public final class CreateTricksItems {
	public static final ItemEntry<Item> KINETICS_SPELL_CORE =
			REGISTRATE.item("kinetics_spell_core", KineticsSpellCoreItem::create)
				.recipe((c, p) -> {
					ShapedRecipeBuilder.shaped(RecipeCategory.MISC, c.get())
							.define('L', Items.LEATHER)
							.define('G', Items.GOLD_INGOT)
							.define('T', Items.TUFF)
							.define('C', AllBlocks.COGWHEEL.asItem())
							.define('M', CreateTricksFluids.LIQUID_MANA.getBucket().get())
							.pattern("LGL")
							.pattern("TCT")
							.pattern("TMT")
							.unlockedBy("has_liquid_mana", RegistrateRecipeProvider.has(CreateTricksFluids.LIQUID_MANA.getBucket().get()))
							.save(p, CreateTricks.modLoc(c.getName()));
				})
				.register();

	public static final ItemEntry<SequencedAssemblyItem> INCOMPLETE_AMETHYST_KNOT =
			incompleteKnot("incomplete_amethyst_knot");
	public static final ItemEntry<SequencedAssemblyItem> INCOMPLETE_QUARTZ_KNOT =
			incompleteKnot("incomplete_quartz_knot");
	public static final ItemEntry<SequencedAssemblyItem> INCOMPLETE_EMERALD_KNOT =
			incompleteKnot("incomplete_emerald_knot");
	public static final ItemEntry<SequencedAssemblyItem> INCOMPLETE_DIAMOND_KNOT =
			incompleteKnot("incomplete_diamond_knot");
	public static final ItemEntry<SequencedAssemblyItem> INCOMPLETE_ECHO_KNOT =
			incompleteKnot("incomplete_echo_knot");
	public static final ItemEntry<SequencedAssemblyItem> INCOMPLETE_ASTRAL_KNOT =
			incompleteKnot("incomplete_astral_knot");

	private CreateTricksItems() {
	}

	public static void register() {
	}

	private static ItemEntry<SequencedAssemblyItem> incompleteKnot(String name) {
		return REGISTRATE.item(name, SequencedAssemblyItem::new)
				.model(NonNullBiConsumer.noop())
				.register();
	}
}
