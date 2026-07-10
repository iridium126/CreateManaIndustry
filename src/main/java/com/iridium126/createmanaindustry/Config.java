package com.iridium126.createmanaindustry;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

@EventBusSubscriber(modid = CreateManaIndustry.MODID)
public final class Config {
	private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

	private static final ModConfigSpec.DoubleValue MANA_PER_STRESS = BUILDER
			.comment("Mana added per stress unit consumed each tick when converting kinetic stress into knot mana.")
			.defineInRange("manaPerStress", 0.001, 0.0, 1000000.0);

	private static final ModConfigSpec.IntValue MANA_PER_BUCKET = BUILDER
			.comment("The amount of mana contained in one bucket (1000mB) of Liquid Mana.")
			.defineInRange("manaPerBucket", 2048, 1, 1000000);

	private static final ModConfigSpec.DoubleValue KINETIC_STRESS_TRICK_MANA_MULTIPLIER = BUILDER
			.comment("The multiplier applied to the kinetic stress mana trick when costing mana.")
			.defineInRange("kineticStressTrickManaMultiplier", 2.0, 0.0, 1000000.0);

	private static final ModConfigSpec.IntValue MIST_MAX_RADIUS = BUILDER
			.comment("Maximum Manhattan radius (in blocks) of the mist field around an active Kinetic Atomizer.")
			.defineInRange("mistMaxRadius", 8, 1, 32);

	private static final ModConfigSpec.IntValue MIST_FLUID_PER_TICK = BUILDER
			.comment("Base fluid amount (mB) consumed per tick when the atomizer is running at 16 RPM. Scales linearly with actual speed.")
			.defineInRange("mistFluidPerTick", 1, 1, 100);

	private static final ModConfigSpec.DoubleValue MIST_BASE_CONCENTRATION = BUILDER
			.comment("Base concentration at distance 0 from the atomizer. Used in the formula: concentration = base * (1 - distance / radius).")
			.defineInRange("mistBaseConcentration", 1.0, 0.0, 1000.0);

	public static final ModConfigSpec SPEC = BUILDER.build();

	public static double manaPerStress = 0.001;
	public static int manaPerBucket = 2048;
	public static double kineticStressTrickManaMultiplier = 2.0;
	public static int mistMaxRadius = 8;
	public static int mistFluidPerTick = 1;
	public static double mistBaseConcentration = 1.0;

	private Config() {}

	@SubscribeEvent
	static void onLoad(ModConfigEvent event) {
		if (event.getConfig().getSpec() == SPEC) {
			manaPerStress = MANA_PER_STRESS.get();
			manaPerBucket = MANA_PER_BUCKET.get();
			kineticStressTrickManaMultiplier = KINETIC_STRESS_TRICK_MANA_MULTIPLIER.get();
			mistMaxRadius = MIST_MAX_RADIUS.get();
			mistFluidPerTick = MIST_FLUID_PER_TICK.get();
			mistBaseConcentration = MIST_BASE_CONCENTRATION.get();
		}
	}
}
