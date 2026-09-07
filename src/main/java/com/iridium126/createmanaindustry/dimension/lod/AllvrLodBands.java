package com.iridium126.createmanaindustry.dimension.lod;

/**
 * The 4c level/band table (grilling 2026-09-06, fixed absolute distances —
 * the view distance only caps the outer edge): full-resolution cube streaming
 * covers 0–256, then L0 256–512, L1 512–1024, L2 1024–2048, L3 2048–R.
 * Vertical range equals the horizontal band (Chebyshev metric, cube-aligned,
 * so the full-res↔LOD boundary never double-covers or gaps).
 * <p>
 * Kept as mutable-in-principle constants: if the 4c-1 smoke fill-time
 * measurement disappoints, demoting the L2 band to L3 is a one-line change
 * here (grilling Q5 decision).
 */
public final class AllvrLodBands {

    public static final int MAX_LEVEL = AllvrLodPos.MAX_LEVEL;

    /** Chebyshev block distance where level L's band starts. */
    private static final int[] BAND_MIN = {256, 512, 1024, 2048};

    public static int bandMin(int level) {
        return BAND_MIN[level];
    }

    /** Band outer edge for a level: fixed table, capped by the view distance.
     *  The top level's band is inclusive of the edge (its upper bound IS R). */
    public static int bandMax(int level, int viewDistanceBlocks) {
        if (!enabled(level, viewDistanceBlocks)) {
            return 0;
        }
        int fixed = level >= MAX_LEVEL ? Integer.MAX_VALUE : BAND_MIN[level + 1];
        return Math.min(fixed, viewDistanceBlocks);
    }

    public static boolean enabled(int level, int viewDistanceBlocks) {
        return level >= 0 && level <= MAX_LEVEL && viewDistanceBlocks >= BAND_MIN[level];
    }

    public static boolean inBand(int level, int chebyshevBlocks, int viewDistanceBlocks) {
        return enabled(level, viewDistanceBlocks)
            && chebyshevBlocks >= BAND_MIN[level]
            && chebyshevBlocks <= bandMax(level, viewDistanceBlocks);
    }

    /**
     * Cells per axis of a level's surface-node bitmap box (player-centered,
     * spanning ±bandMax). The box is <b>symmetric and inclusive of the band
     * edge</b> (plan F19): a level-L node whose AABB merely touches the band
     * outer edge lives at cell distance {@code bandMax/cellBlocks} from the
     * player, so the box must cover {@code -d..d} (2d+1 cells, d =
     * bandMax/cellBlocks). The old even {@code 2*R/size} box was half-open —
     * it dropped the +edge cell row and created the request/forget gap the
     * server's own inBand table accepts (the [512,575] hole). The client walk
     * crops to the same {@link #maxCellDistance} so box and band agree.
     */
    public static int bitmapBoxCells(int level, int viewDistanceBlocks) {
        if (!enabled(level, viewDistanceBlocks)) {
            return 0;
        }
        return 2 * (bandMax(level, viewDistanceBlocks) / cellBlocks(level)) + 1;
    }

    /**
     * Maximum cell distance (from the player's own cell) a level's node may
     * sit at and still satisfy the server's AABB-based {@code inBand} check:
     * cell distance d ⇒ AABB distance ≤ d·cellBlocks, so d ≤ bandMax/cell.
     * The bitmap box half-extent equals this value (plan F19).
     */
    public static int maxCellDistance(int level, int viewDistanceBlocks) {
        if (!enabled(level, viewDistanceBlocks)) {
            return 0;
        }
        return bandMax(level, viewDistanceBlocks) / cellBlocks(level);
    }

    /** Player movement (in the level's own cells) before the bitmap is
     *  recomputed and resent — 8 cells at L0–L2, box/4 for narrow L3 boxes. */
    public static int resendThresholdCells(int level, int viewDistanceBlocks) {
        return Math.max(1, bitmapBoxCells(level, viewDistanceBlocks) / 4);
    }

    /**
     * The near/full-res streaming radius a player's own level-0 cell
     * distance must clear before LOD owns the node (plan F19): the server's
     * inBand floor is bandMin blocks measured on the node AABB; cell distance
     * d ⇒ AABB distance ≥ (d−1)·cellBlocks, so d ≥ bandMin/cell + 1.
     * Shared by the client walk so both sides derive ownership from one
     * definition instead of duplicated constants.
     */
    public static int minCellDistance(int level) {
        return bandMin(level) / cellBlocks(level) + 1;
    }

    /** Blocks per cell of a level's node grid (32, 64, 128, 256). */
    public static int cellBlocks(int level) {
        return 32 << level;
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
     *  radius plus the same 25% hysteresis the bitmap box uses, so nodes do
     *  not flap at the window edge. Never exceeds the band box itself. */
    public static int verticalEvictCells(int level, int viewDistanceBlocks) {
        int box = bitmapBoxCells(level, viewDistanceBlocks);
        if (box <= 0) {
            return 0;
        }
        int active = activeVerticalCells(level);
        int hysteresis = active + Math.max(1, active >> 2);
        return Math.min(box >> 1, hysteresis);
    }

    /**
     * Block extent the near (full-resolution) stream actually covers — the
     * fog-end target of the near-only state (plan F18): send radius plus the
     * forget hysteresis, computed from the real constants instead of a
     * hand-maintained 320 that silently drifted from them.
     */
    public static int fullResExtentBlocks() {
        return (8 + 2) * 32;
    }

    private AllvrLodBands() {}
}
