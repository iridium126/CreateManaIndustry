package com.iridium126.createmanaindustry.dimension;

import net.minecraft.core.BlockPos;

/**
 * Software play-area bounds of the allay dimension.
 * <p>
 * The cube coordinate encoding ({@code AllvrCubePos}, 21 bit per axis) can
 * address ±33,554,431 blocks; these limits are the tighter gameplay window
 * enforced at block-write and entity-movement time. XZ deliberately matches
 * the vanilla default world border (±29,999,984, derived from the 26 bit
 * BlockPos X/Z packing) so the dimension's horizontal play area is identical
 * to the overworld's.
 */
public final class AllvrDimensionLimits {

    public static final int VANILLA_MIN_Y = -128;
    public static final int VANILLA_MAX_Y = 384;

    /** One extra chunk of prefetch keeps crossings ahead of the visible band. */
    public static boolean intersectsVanillaView(double playerY, int viewDistanceChunks) {
        int reach = (viewDistanceChunks + 1) * 16;
        return playerY + reach >= VANILLA_MIN_Y && playerY - reach < VANILLA_MAX_Y;
    }

    public static boolean isVanillaY(int y) {
        return y >= VANILLA_MIN_Y && y < VANILLA_MAX_Y;
    }

    public static boolean isVanillaSection(int sectionY) {
        return sectionY >= (VANILLA_MIN_Y >> 4) && sectionY < (VANILLA_MAX_Y >> 4);
    }

    public static boolean isVanillaCube(int cubeY) {
        return cubeY >= (VANILLA_MIN_Y >> 5) && cubeY < (VANILLA_MAX_Y >> 5);
    }

    /** Soft Y boundary, ±. Inside the CubePos encoding range with ~11% headroom. */
    public static final int Y_BOUND = 30_000_000;

    /** Horizontal boundary, ±. Equals the vanilla default world border. */
    public static final int XZ_BOUND = 29_999_984;

    public static boolean isInBounds(BlockPos pos) {
        return isInBounds(pos.getX(), pos.getY(), pos.getZ());
    }

    public static boolean isInBounds(int x, int y, int z) {
        return x >= -XZ_BOUND && x <= XZ_BOUND
            && y >= -Y_BOUND && y <= Y_BOUND
            && z >= -XZ_BOUND && z <= XZ_BOUND;
    }

    public static int clampY(int y) {
        return Math.max(-Y_BOUND, Math.min(Y_BOUND, y));
    }

    private AllvrDimensionLimits() {}
}
