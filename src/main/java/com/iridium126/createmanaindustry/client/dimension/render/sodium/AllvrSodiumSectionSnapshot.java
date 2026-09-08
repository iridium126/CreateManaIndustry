package com.iridium126.createmanaindustry.client.dimension.render.sodium;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Immutable section input handed to a Sodium build task.  The section is
 * copied while the cube-cache lock is held; Sodium workers never retain a
 * live cube palette.  Coordinates are split explicitly so the renderer can
 * use the virtual key while model/light semantics retain the absolute key.
 */
public record AllvrSodiumSectionSnapshot(
    SectionPos virtualPos,
    SectionPos absolutePos,
    long cubeKey,
    long cubeRevision,
    long contentRevision,
    long resourceRevision,
    long windowEpoch,
    LevelChunkSection section
) {
    public boolean isEmpty() {
        return this.section == null || this.section.hasOnlyAir();
    }
}
