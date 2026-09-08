package com.iridium126.createmanaindustry.dimension.lod;

/**
 * Geometry constants for the four Allay LOD representations.
 *
 * <p>This class deliberately does not assign a distance band to a level.  A
 * level describes the spatial resolution of a payload (32, 64, 128 or 256
 * blocks per node); Voxy owns the screen-space choice of which available
 * level to draw.  The server distance remains only a bounded data-coverage
 * limit, while the client near exclusion is derived from Minecraft's current
 * render distance.</p>
 */
public final class AllvrLodBands {

    public static final int MAX_LEVEL = AllvrLodPos.MAX_LEVEL;
    /** Maximum bitmap edge when the server's 4096-block coverage limit is used. */
    public static final int MAX_BITMAP_DIM = 257;

    public static boolean enabled(int level, int viewDistanceBlocks) {
        return level >= 0 && level <= MAX_LEVEL && viewDistanceBlocks > 0;
    }

    /** Whether a node is inside the server's bounded far-data coverage. */
    public static boolean inCoverage(int level, int chebyshevBlocks, int viewDistanceBlocks) {
        return enabled(level, viewDistanceBlocks)
            && chebyshevBlocks <= viewDistanceBlocks;
    }

    /**
     * Cells per axis of a level's player-centred coverage bitmap. Every level
     * gets the same world-space coverage; only its cell size changes. This is
     * intentional: it gives Voxy a complete hierarchy instead of preselecting
     * one LOD level from a distance table.
     */
    public static int bitmapBoxCells(int level, int viewDistanceBlocks) {
        if (!enabled(level, viewDistanceBlocks)) {
            return 0;
        }
        return 2 * maxCellDistance(level, viewDistanceBlocks) + 1;
    }

    /**
     * Maximum cell distance needed to cover the configured world-space extent.
     * The ceiling includes the cell that touches the outer edge.
     */
    public static int maxCellDistance(int level, int viewDistanceBlocks) {
        if (!enabled(level, viewDistanceBlocks)) {
            return 0;
        }
        int size = cellBlocks(level);
        return (viewDistanceBlocks + size - 1) / size;
    }

    /** Player movement (in the level's own cells) before the bitmap is
     *  recomputed and resent. */
    public static int resendThresholdCells(int level, int viewDistanceBlocks) {
        return Math.max(1, bitmapBoxCells(level, viewDistanceBlocks) / 4);
    }

    /** Blocks per cell of a level's node grid (32, 64, 128, 256). */
    public static int cellBlocks(int level) {
        return 32 << level;
    }

    /**
     * Chebyshev distance in blocks from a player block to the nearest point of
     * a level cell. The near/far seam uses this rather than a level-specific
     * cell-distance table, so every resolution follows the same Minecraft
     * render-distance boundary.
     */
    public static int nearestDistanceBlocks(int level, int cellX, int cellY, int cellZ,
                                            int blockX, int blockY, int blockZ) {
        int size = cellBlocks(level);
        long minX = (long) cellX * size;
        long minY = (long) cellY * size;
        long minZ = (long) cellZ * size;
        int dx = distanceToCellAxis(blockX, minX, size);
        int dy = distanceToCellAxis(blockY, minY, size);
        int dz = distanceToCellAxis(blockZ, minZ, size);
        return Math.max(dx, Math.max(dy, dz));
    }

    private static int distanceToCellAxis(int block, long min, int size) {
        long maxExclusive = min + size;
        long distance = block < min ? min - block
            : block >= maxExclusive ? block - maxExclusive : 0L;
        return (int) Math.min(Integer.MAX_VALUE, distance);
    }

    /**
     * Active vertical radius in blocks for LOD requests and injection
     * (voxy integration plan §5.2): fixed 3072. Requests, eviction and the
     * client's virtual-Y window are cropped to this around the player —
     * a larger value could push owned nodes past the Voxy section-key's
     * hard ±4096 bound even before a rebase, and 3072 keeps ≥512 blocks
     * of guard band from that edge at every level.
     */
    public static final int ACTIVE_VERTICAL_RADIUS_BLOCKS = 3072;

    /** Active vertical radius in a level's own cells (96/48/24/12). */
    public static int activeVerticalCells(int level) {
        return ACTIVE_VERTICAL_RADIUS_BLOCKS >> (5 + level);
    }

    /** Vertical eviction limit (cells from the player's cell): the active
     *  radius plus the same 25% hysteresis the bitmap box uses. */
    public static int verticalEvictCells(int level, int viewDistanceBlocks) {
        int box = bitmapBoxCells(level, viewDistanceBlocks);
        if (box <= 0) {
            return 0;
        }
        int active = activeVerticalCells(level);
        int hysteresis = active + Math.max(1, active >> 2);
        return Math.min(box >> 1, hysteresis);
    }

    private AllvrLodBands() {}
}
