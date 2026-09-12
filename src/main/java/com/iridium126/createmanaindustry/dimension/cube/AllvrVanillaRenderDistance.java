package com.iridium126.createmanaindustry.dimension.cube;

/**
 * Vanilla-compatible horizontal view-distance geometry for Allay cubes.
 *
 * <p>Minecraft tracks chunks in the X/Z plane with the same squared-distance
 * test used by {@code ChunkTrackingView}. An Allay cube contains a 2x2 block
 * of vanilla chunks, so a cube is visible when at least one of those chunks is
 * in the vanilla view. The independent cube-Y check turns that circle into a
 * cylinder without changing Allay's vertical coverage.</p>
 */
public final class AllvrVanillaRenderDistance {

    public static final int MIN_CHUNKS = 2;
    public static final int MAX_CHUNKS = 32;
    public static final int CHUNKS_PER_CUBE = 2;
    public static final int CUBE_BLOCKS = 32;

    private AllvrVanillaRenderDistance() {}

    public static int clampChunks(int chunks) {
        return Math.max(MIN_CHUNKS, Math.min(MAX_CHUNKS, chunks));
    }

    /**
     * Exact copy of vanilla's {@code ChunkTrackingView.isWithinDistance(...,
     * includeOuterChunksAdjacentToViewBorder=true)} geometry.
     */
    public static boolean isWithinVanillaChunkDistance(int centerX, int centerZ,
                                                        int viewDistance,
                                                        int chunkX, int chunkZ) {
        int i = Math.max(0, Math.abs(chunkX - centerX) - 1);
        int j = Math.max(0, Math.abs(chunkZ - centerZ) - 1);
        long k = Math.max(0, Math.max(i, j) - 1L);
        long l = Math.min(i, j);
        long distanceSquared = l * l + k * k;
        int clampedDistance = clampChunks(viewDistance);
        long radiusSquared = (long) clampedDistance * clampedDistance;
        return distanceSquared < radiusSquared;
    }

    /**
     * Tests an Allay cube against the vanilla horizontal circle and the
     * retained independent vertical render radius. The cube is accepted if
     * any of its four X/Z vanilla chunks is tracked by vanilla; this avoids a
     * hole when a 32-block transport cube straddles the circle boundary.
     */
    public static boolean isCubeWithinCylinder(AllvrCubePos cube,
                                               int playerChunkX,
                                               int playerChunkZ,
                                               int playerCubeY,
                                               int renderDistanceChunks,
                                               int verticalCubeRadius) {
        return isCubeWithinCylinder(cube.getX(), cube.getY(), cube.getZ(),
            playerChunkX, playerChunkZ, playerCubeY, renderDistanceChunks, verticalCubeRadius);
    }

    /**
     * Allocation-free form used by the server tracking hot path. The
     * geometry is deliberately shared with the object overload above so the
     * tracking result cannot diverge from vanilla's circle-to-cylinder rule.
     */
    public static boolean isCubeWithinCylinder(int cubeX, int cubeY, int cubeZ,
                                               int playerChunkX,
                                               int playerChunkZ,
                                               int playerCubeY,
                                               int renderDistanceChunks,
                                               int verticalCubeRadius) {
        if (Math.abs(cubeY - playerCubeY) > verticalCubeRadius) {
            return false;
        }

        int firstChunkX = cubeX * CHUNKS_PER_CUBE;
        int firstChunkZ = cubeZ * CHUNKS_PER_CUBE;
        int viewDistance = clampChunks(renderDistanceChunks);
        for (int chunkX = firstChunkX; chunkX < firstChunkX + CHUNKS_PER_CUBE; chunkX++) {
            for (int chunkZ = firstChunkZ; chunkZ < firstChunkZ + CHUNKS_PER_CUBE; chunkZ++) {
                if (isWithinVanillaChunkDistance(playerChunkX, playerChunkZ,
                    viewDistance, chunkX, chunkZ)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Chebyshev scan bound for the circle. The extra chunk accounts for
     * vanilla's outer tracked ring and the extra half-cube when converting
     * two-chunk-wide transport cubes to a cube-grid radius.
     */
    public static int cubeScanRadiusForChunks(int renderDistanceChunks) {
        return Math.max(1, (clampChunks(renderDistanceChunks) + 3) / 2);
    }

    public static int blockToChunk(double block) {
        return Math.floorDiv((int) Math.floor(block), 16);
    }
}
