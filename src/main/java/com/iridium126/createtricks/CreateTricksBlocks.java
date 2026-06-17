package com.iridium126.createtricks;

import static com.iridium126.createtricks.CreateTricks.REGISTRATE;

import com.iridium126.createtricks.content.kinetics.StressManaConverterBlock;
import com.iridium126.createtricks.content.kinetics.StressManaConverterGenerator;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.foundation.data.ModelGen;
import com.simibubi.create.foundation.data.TagGen;
import com.tterrag.registrate.providers.RegistrateRecipeProvider;
import com.tterrag.registrate.util.entry.BlockEntry;

import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.ShapedRecipeBuilder;

public final class CreateTricksBlocks {
	public static final BlockEntry<StressManaConverterBlock> STRESS_MANA_CONVERTER = REGISTRATE
			.block("stress_mana_converter", StressManaConverterBlock::new)
			.initialProperties(() -> Blocks.IRON_BLOCK)
			.properties(p -> p.mapColor(MapColor.TERRACOTTA_YELLOW))
			.blockstate(StressManaConverterGenerator.directionalBlockState())
			.transform(TagGen.pickaxeOnly())
			.item()
			.transform(ModelGen.customItemModel())
			.recipe((c, p) -> {
				ShapedRecipeBuilder.shaped(RecipeCategory.MISC, c.get())
						.define('A', Items.AMETHYST_BLOCK)
						.define('L', CreateTricksFluids.LIQUID_MANA.getBucket().get())
						.define('C', AllBlocks.COGWHEEL.asItem())
						.define('B', AllBlocks.BRASS_BLOCK.asItem())
						.pattern("AAA")
						.pattern("LCL")
						.pattern("BBB")
						.unlockedBy("has_liquid_mana", RegistrateRecipeProvider.has(CreateTricksFluids.LIQUID_MANA.getBucket().get()))
						.save(p, CreateTricks.modLoc(c.getName()));
			})
			.register();

	private CreateTricksBlocks() {
	}

	public static void register() {
	}
}
