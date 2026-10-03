package com.iridium126.createmanaindustry.client.particles.packages;

import net.minecraft.core.BlockPos;

/** Converts a plot's parent-storage block coordinate to EmbeddedPlotLevelAccessor coordinates. */
final class PackageSableCollisionCoordinates {
    private PackageSableCollisionCoordinates() {}

    static BlockPos.MutableBlockPos contextPosition(BlockPos storagePosition, BlockPos center,
                                                     BlockPos.MutableBlockPos target) {
        return target.set(storagePosition.getX() - center.getX(), storagePosition.getY() - center.getY(),
                storagePosition.getZ() - center.getZ());
    }
}
