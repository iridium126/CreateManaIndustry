package com.iridium126.createtricks.content.kinetics;

import com.simibubi.create.foundation.data.AssetLookup;
import com.tterrag.registrate.providers.DataGenContext;
import com.tterrag.registrate.providers.RegistrateBlockstateProvider;
import com.tterrag.registrate.util.nullness.NonNullBiConsumer;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;

public class StressManaConverterGenerator {
	public static <T extends Block> NonNullBiConsumer<DataGenContext<Block, T>, RegistrateBlockstateProvider> directionalBlockState() {
        return (ctx, prov) -> directionalModel(ctx, prov);
    }

	public static <T extends Block> void directionalModel(DataGenContext<Block, T> ctx, RegistrateBlockstateProvider prov) {
		prov.getVariantBuilder(ctx.get())
                .forAllStatesExcept(state -> {
            Direction dir = state.getValue(BlockStateProperties.FACING);
            return ConfiguredModel.builder()
                    .modelFile(AssetLookup.partialBaseModel(ctx, prov))
                    .rotationX(dir == Direction.DOWN ? 270
                            : dir.getAxis()
                            .isHorizontal() ? 0 : 90)
                    .rotationY(dir.getAxis()
                            .isVertical() ? 90 : (((int) dir.toYRot()) + 360) % 360)
                    .build();
        }, BlockStateProperties.WATERLOGGED, BlockStateProperties.POWERED);
	}

}
