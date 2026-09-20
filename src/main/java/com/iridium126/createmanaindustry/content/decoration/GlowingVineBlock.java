package com.iridium126.createmanaindustry.content.decoration;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Vanilla VineBlock placement and support behaviour with growth deliberately
 * disabled. Client particle discovery is handled by the GPU particle range
 * index, so this block does not enter the high-frequency animate-tick path.
 */
public final class GlowingVineBlock extends VineBlock {
    public GlowingVineBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected void randomTick(BlockState state, net.minecraft.server.level.ServerLevel level,
            BlockPos pos, RandomSource random) {
        // Intentionally empty: this block keeps vanilla vine attachment and
        // survival rules, but never spreads or grows.
    }
}
