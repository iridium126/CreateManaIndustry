package com.iridium126.createmanaindustry.client.dimension;

import net.minecraft.client.Minecraft;

/**
 * The one client-side source of truth for Allay's near render distance.
 * Sodium and Voxy both use Minecraft's effective render distance, so the
 * Allay bridge must use the same value when deciding how much near data to
 * request from the server.  The conversion helpers are kept free of any
 * Cube/LOD state so packet and geometry code cannot grow another fixed range.
 */
public final class AllvrClientRenderDistance {

    /** A vanilla client never exposes a useful render distance below two. */
    private static final int MIN_RENDER_DISTANCE_CHUNKS = 2;
    /** Allay's storage/render cube edge is fixed at 32 blocks. */
    public static final int CUBE_BLOCKS = 32;

    private AllvrClientRenderDistance() {}

    /** Minecraft's effective render distance in chunks (the Sodium/Voxy value). */
    public static int chunks() {
        return Math.max(MIN_RENDER_DISTANCE_CHUNKS,
            Minecraft.getInstance().options.getEffectiveRenderDistance());
    }

    /** The vanilla near render distance in blocks. */
    public static int blocks() {
        return blocksForChunks(chunks());
    }

    public static int blocksForChunks(int renderDistanceChunks) {
        return Math.max(MIN_RENDER_DISTANCE_CHUNKS, renderDistanceChunks) * 16;
    }

    /** Number of 32-block cubes needed to cover the near distance. */
    public static int cubeRadiusForChunks(int renderDistanceChunks) {
        return Math.max(1, (blocksForChunks(renderDistanceChunks) + CUBE_BLOCKS - 1)
            / CUBE_BLOCKS);
    }
}
