package com.iridium126.createmanaindustry.client.dimension.lod.voxy;

/**
 * The player-centered virtual Y window (voxy integration plan §5): Voxy's
 * section key keeps only 8 signed bits of Y (L0 section = 32 blocks, so the
 * hard window is block Y {@code [-4096, 4095]} = cell Y {@code [-128, 127]}),
 * while ALLVR spans ±30M. The window is a 512-block-aligned
 * {@code originBlockY} tracked near the player; the client remaps absolute
 * cell Y → virtual cell Y at injection and camera Y at the viewport patch.
 * The server never sees a virtual Y, and virtual keys never persist (the
 * allay voxy engine runs on memory storage with {@code DONT_SAVE} writes).
 */
public final class AllvrVoxyYWindow {

    /** Voxy {@code WorldEngine.MAX_LOD_LAYER} (verified value 4). */
    public static final int MAX_VOXY_LEVEL = 4;
    /** One top-level node's edge in blocks: {@code 32 << 4}. */
    public static final int ALIGN_BLOCKS = 32 << MAX_VOXY_LEVEL;
    /** Voxy hard cell-Y range for the section key: {@code [-128, 127]}. */
    public static final int VOXY_MIN_CELL_Y = -128;
    public static final int VOXY_MAX_CELL_Y = 127;
    /** Voxy hard block-Y range implied by the cell range: {@code [-4096, 4095]}. */
    public static final int VOXY_MIN_BLOCK_Y = VOXY_MIN_CELL_Y << 5;
    public static final int VOXY_MAX_BLOCK_Y = (VOXY_MAX_CELL_Y << 5) + 31;
    /** Rebase trigger distance from the origin (plan §5.2). */
    private static final int REBASE_TRIGGER_BLOCKS = 512;

    private volatile long epoch;
    private volatile int originBlockY;

    public AllvrVoxyYWindow() {
        this.originBlockY = 0;
    }

    /** Monotonic window generation — bumped at every rebase; late section
     *  payloads from an older epoch must be dropped, not resurrected. */
    public long epoch() {
        return this.epoch;
    }

    public int originBlockY() {
        return this.originBlockY;
    }

    /** Virtual cell Y for one ABSOLUTE cell Y at a level (plan §5.1). */
    public int virtualCellY(int level, int absoluteCellY) {
        return absoluteCellY - (this.originBlockY >> (5 + level));
    }

    /** Camera remap for the viewport patch (plan §5.1). */
    public double virtualCameraY(double absoluteCameraY) {
        return absoluteCameraY - this.originBlockY;
    }

    /** True while the player sits inside the rebase trigger band. */
    public boolean needsRebase(double playerY) {
        return Math.abs(playerY - this.originBlockY) >= REBASE_TRIGGER_BLOCKS;
    }

    /** The nearest 512-aligned origin for a player Y (plan §5.2). */
    public int nextOrigin(double playerY) {
        return (int) Math.rint(playerY / (double) ALIGN_BLOCKS) * ALIGN_BLOCKS;
    }

    /** Moves the origin and bumps the epoch (plan §5.3 MOVE step). */
    public void moveOrigin(int newOrigin) {
        this.originBlockY = newOrigin;
        this.epoch++;
    }

    /** Invalidates queued work before a rebase MOVE publishes a new origin. */
    public void invalidateQueuedWork() {
        this.epoch++;
    }
}
