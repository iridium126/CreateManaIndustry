package com.iridium126.createtricks;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

@EventBusSubscriber(modid = CreateTricks.MODID)
public final class Config {
	private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

	private static final ModConfigSpec.DoubleValue MANA_PER_STRESS = BUILDER
			.comment("Mana added per stress unit consumed each tick when converting kinetic stress into knot mana.")
			.defineInRange("manaPerStress", 0.001, 0.0, 1000000.0);

	private static final ModConfigSpec.IntValue MANA_PER_BUCKET = BUILDER
			.comment("The amount of mana contained in one bucket (1000mB) of Liquid Mana.")
			.defineInRange("manaPerBucket", 2048, 1, 1000000);

	public static final ModConfigSpec SPEC = BUILDER.build();

	public static double manaPerStress = 0.001;
	public static int manaPerBucket = 2048;

	private Config() {}

	@SubscribeEvent
	static void onLoad(ModConfigEvent event) {
		if (event.getConfig().getSpec() == SPEC) {
			manaPerStress = MANA_PER_STRESS.get();
			manaPerBucket = MANA_PER_BUCKET.get();
		}
	}
}
