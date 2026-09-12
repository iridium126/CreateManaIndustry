package com.iridium126.createmanaindustry.client.dimension;

import net.minecraft.client.Minecraft;

import com.iridium126.createmanaindustry.dimension.cube.AllvrVanillaRenderDistance;

/**
 * The one client-side source of truth for Allay's near render distance.
 * Sodium and Voxy both use Minecraft's effective render distance, so the
 * Allay bridge must use the same value when deciding how much near data to
 * request from the server.  The conversion helpers are kept free of any
 * Cube and renderer state so packet and geometry code cannot grow another
 * fixed range.
 */
public final class AllvrClientRenderDistance {

    /** Allay's storage/render cube edge is fixed at 32 blocks. */
    public static final int CUBE_BLOCKS = AllvrVanillaRenderDistance.CUBE_BLOCKS;

    private AllvrClientRenderDistance() {}

    /** Minecraft's effective render distance in chunks (the Sodium/Voxy value). */
    public static int chunks() {
        return AllvrVanillaRenderDistance.clampChunks(
            Minecraft.getInstance().options.getEffectiveRenderDistance());
    }

    /** The vanilla near render distance in blocks. */
    public static int blocks() {
        return blocksForChunks(chunks());
    }

    public static int blocksForChunks(int renderDistanceChunks) {
        return AllvrVanillaRenderDistance.clampChunks(renderDistanceChunks) * 16;
    }

    /** Number of 32-block cubes needed to cover the near distance. */
    public static int cubeRadiusForChunks(int renderDistanceChunks) {
        return Math.max(1, (blocksForChunks(renderDistanceChunks) + CUBE_BLOCKS - 1)
            / CUBE_BLOCKS);
    }
}
