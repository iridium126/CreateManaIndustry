package com.iridium126.createmanaindustry;

import static com.iridium126.createmanaindustry.CreateManaIndustry.REGISTRATE;

import com.iridium126.createmanaindustry.content.kinetics.kineticatomizer.KineticAtomizerBlock;
import com.iridium126.createmanaindustry.content.kinetics.stressmanaconverter.StressManaConverterBlock;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.foundation.data.BlockStateGen;
import com.simibubi.create.foundation.data.ModelGen;
import com.simibubi.create.foundation.data.TagGen;
import com.tterrag.registrate.providers.RegistrateRecipeProvider;
import com.tterrag.registrate.util.entry.BlockEntry;

import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.ShapedRecipeBuilder;

public final class CMIBlocks {
	public static final BlockEntry<StressManaConverterBlock> STRESS_MANA_CONVERTER = REGISTRATE
			.block("stress_mana_converter", StressManaConverterBlock::new)
			.initialProperties(() -> Blocks.IRON_BLOCK)
			.properties(p -> p.mapColor(MapColor.TERRACOTTA_YELLOW))
			.blockstate(BlockStateGen.directionalBlockProvider(true))
			.transform(TagGen.pickaxeOnly())
			.item()
			.transform(ModelGen.customItemModel())
			.recipe((c, p) -> {
				ShapedRecipeBuilder.shaped(RecipeCategory.MISC, c.get())
						.define('A', Items.AMETHYST_BLOCK)
						.define('L', CMIFluids.LIQUID_MANA.getBucket().get())
						.define('C', AllBlocks.COGWHEEL.asItem())
						.define('B', AllBlocks.BRASS_BLOCK.asItem())
						.pattern("AAA")
						.pattern("LCL")
						.pattern("BBB")
						.unlockedBy("has_liquid_mana", RegistrateRecipeProvider.has(CMIFluids.LIQUID_MANA.getBucket().get()))
						.save(p, CreateManaIndustry.modLoc(c.getName()));
			})
			.register();

	public static final BlockEntry<KineticAtomizerBlock> KINETIC_ATOMIZER = REGISTRATE
			.block("kinetic_atomizer", KineticAtomizerBlock::new)
			.initialProperties(() -> Blocks.IRON_BLOCK)
			.properties(p -> p.mapColor(MapColor.TERRACOTTA_YELLOW))
			.blockstate(BlockStateGen.directionalBlockProvider(true))
			.transform(TagGen.pickaxeOnly())
			.item()
			.transform(ModelGen.customItemModel())
			.recipe((c, p) -> {
				ShapedRecipeBuilder.shaped(RecipeCategory.MISC, c.get())
						.define('C', AllBlocks.COGWHEEL.asItem())
						.define('P', AllBlocks.FLUID_PIPE.asItem())
						.define('B', AllBlocks.BRASS_CASING.asItem())
						.define('N', Items.COPPER_INGOT)
						.pattern(" N ")
						.pattern("BCB")
						.pattern(" P ")
						.unlockedBy("has_liquid_mana", RegistrateRecipeProvider.has(CMIFluids.LIQUID_MANA.getBucket().get()))
						.save(p, CreateManaIndustry.modLoc(c.getName()));
			})
			.register();

	private CMIBlocks() {
	}

	public static void register() {
	}
}
