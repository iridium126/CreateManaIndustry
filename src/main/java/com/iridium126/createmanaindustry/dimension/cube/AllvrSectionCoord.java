package com.iridium126.createmanaindustry.dimension.cube;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

/** Absolute coordinates. Never serialize absolute Y through SectionPos.asLong. */
public record AllvrSectionCoord(int x, int y, int z) {
    public static AllvrSectionCoord of(BlockPos pos) {
        return new AllvrSectionCoord(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }
    public SectionPos sectionPos() { return SectionPos.of(x, y, z); }
    public BlockPos origin() { return new BlockPos(x << 4, y << 4, z << 4); }
    public long columnKey() { return ChunkPos.asLong(x, z); }
    public long cubeKey() { return AllvrCubePos.asLong(x >> 1, y >> 1, z >> 1); }
}
