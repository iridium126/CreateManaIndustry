package com.iridium126.createmanaindustry.dimension.cube;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Read-only block/light view the LOD overlay capture reads — implemented by
 * the live {@link AllvrCube} and by the persistence layer's
 * {@code AllvrPersistedOverlay} (restored-but-not-installed cubes), so the
 * capture scan is one code path for both (plan §7.6). Implementations must
 * never be touched by more than one thread at a time.
 */
public interface AllvrOverlaySource {

    AllvrCubePos getPos();

    BlockState getBlockState(BlockPos worldPos);

    /** Cell index → light emission map of the cube. */
    Int2IntMap getEmitters();
}
