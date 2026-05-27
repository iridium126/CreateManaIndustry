package com.iridium126.createtricks;

import static com.iridium126.createtricks.CreateTricks.REGISTRATE;

import com.tterrag.registrate.util.entry.FluidEntry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.DispensibleContainerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;

public class CreateTricksFluids {
	public static final FluidEntry<BaseFlowingFluid.Flowing> LIQUID_MANA =
			REGISTRATE.standardFluid("liquid_mana")
					.properties(b -> b.viscosity(1000).density(1000).lightLevel(15))
					.fluidProperties(p -> p.levelDecreasePerBlock(1)
							.tickRate(5)
							.slopeFindDistance(4)
							.explosionResistance(100f))
					.source(BaseFlowingFluid.Source::new)
					.block()
					.properties(p -> p.mapColor(MapColor.COLOR_PURPLE))
					.build()
					.bucket()
					.onRegister(CreateTricksFluids::registerFluidDispenseBehavior)
					.tag(Tags.Items.BUCKETS)
					.build()
					.register();

	public static void register() {}

	private static final DispenseItemBehavior DISPENSE_FLUID = new DefaultDispenseItemBehavior() {
		@Override
		protected ItemStack execute(BlockSource pSource, ItemStack pStack) {
			DispensibleContainerItem dispensibleContainerItem = (DispensibleContainerItem) pStack.getItem();
			BlockPos pos = pSource.pos().relative(pSource.state().getValue(DispenserBlock.FACING));
			Level level = pSource.level();
			if (dispensibleContainerItem.emptyContents(null, level, pos, null, pStack)) {
				return new ItemStack(Items.BUCKET);
			}
			return super.execute(pSource, pStack);
		}
	};

	private static void registerFluidDispenseBehavior(BucketItem bucket) {
		DispenserBlock.registerBehavior(bucket, DISPENSE_FLUID);
	}
}
