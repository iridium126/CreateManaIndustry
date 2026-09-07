package com.iridium126.createmanaindustry.client.dimension.render;

import net.minecraft.world.level.block.state.BlockState;

/** Immutable render-thread handoff for geometry outside the descriptor fast path. */
public record AllvrFallbackBlock(int x, int y, int z, BlockState state,
                                 int packedLight, boolean model, boolean fluid, boolean translucent) {
}
