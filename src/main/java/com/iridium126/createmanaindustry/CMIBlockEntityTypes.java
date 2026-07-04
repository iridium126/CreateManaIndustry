package com.iridium126.createmanaindustry;

import static com.iridium126.createmanaindustry.CreateManaIndustry.REGISTRATE;

import com.iridium126.createmanaindustry.content.kinetics.stressmanaconverter.StressManaConverterBlockEntity;
import com.iridium126.createmanaindustry.content.kinetics.stressmanaconverter.StressManaConverterRenderer;
import com.iridium126.createmanaindustry.content.kinetics.stressmanaconverter.StressManaConverterVisual;
import com.tterrag.registrate.util.entry.BlockEntityEntry;

public final class CMIBlockEntityTypes {
	public static final BlockEntityEntry<StressManaConverterBlockEntity> STRESS_MANA_CONVERTER = REGISTRATE
			.blockEntity("stress_mana_converter", StressManaConverterBlockEntity::new)
			.visual(() -> StressManaConverterVisual::new, false)
			.validBlocks(CMIBlocks.STRESS_MANA_CONVERTER)
			.renderer(() -> StressManaConverterRenderer::new)
			.register();

	private CMIBlockEntityTypes() {}

	public static void register() {}
}
