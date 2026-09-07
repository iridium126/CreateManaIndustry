package com.iridium126.createmanaindustry.dimension.storage;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainerRO;

import com.iridium126.createmanaindustry.dimension.cube.AllvrCoords;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCube;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;
import com.iridium126.createmanaindustry.dimension.cube.AllvrOverlaySource;

/**
 * Immutable block/light view of a persisted-but-not-loaded cube, decoded from
 * its NBT record for the LOD pipeline (plan §7.1/§7.6). Deliberately NOT a
 * live cube: no block entities are created, the cube map is never joined, and
 * nothing ticks — the view only answers block-state and emitter queries so
 * distant edits stay visible after a restart without loading the cube.
 * <p>
 * Decode runs off the server thread (worker/completion threads): the state is
 * fully immutable and the plain {@code getLightEmission()} accessor is used
 * (no level context dereference).
 */
public final class AllvrPersistedOverlay implements AllvrOverlaySource {

    private final AllvrCubePos pos;
    private final PalettedContainerRO<BlockState>[] sections;
    private final Int2IntOpenHashMap emitters;

    AllvrPersistedOverlay(AllvrCubePos pos, PalettedContainerRO<BlockState>[] sections,
                          Int2IntOpenHashMap emitters) {
        this.pos = pos;
        this.sections = sections;
        this.emitters = emitters;
    }

    @Override
    public AllvrCubePos getPos() {
        return this.pos;
    }

    @Override
    public BlockState getBlockState(BlockPos worldPos) {
        int lx = AllvrCoords.blockToLocal(worldPos.getX());
        int ly = AllvrCoords.blockToLocal(worldPos.getY());
        int lz = AllvrCoords.blockToLocal(worldPos.getZ());
        int index = AllvrCube.sliceIndex(lx >> 4, ly >> 4, lz >> 4);
        return this.sections[index].get(lx & 15, ly & 15, lz & 15);
    }

    @Override
    public Int2IntMap getEmitters() {
        return this.emitters;
    }
}
