package com.iridium126.createmanaindustry.client.dimension.lod.voxy;

import com.iridium126.createmanaindustry.client.dimension.render.AllvrRenderYWindow;

/**
 * Voxy-side view of the shared virtual Y window (sodium-parity plan §6.3):
 * origin/epoch/rebase ownership lives in {@link AllvrRenderYWindow} — the
 * Sodium bridge drives the rebase state machine and the Voxy backend observes
 * the epoch. This wrapper only adds the Voxy-specific coordinate surface:
 * Voxy's LOD cell keys exist per level ({@code 32 << level} blocks per cell)
 * and its hard cell-Y range is {@code [-128, 127]} (block Y {@code [-4096,
 * 4095]}). Section keys never persist (the allay voxy engine runs on memory
 * storage with {@code DONT_SAVE} writes).
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

    private final AllvrRenderYWindow window;

    public AllvrVoxyYWindow(AllvrRenderYWindow window) {
        this.window = window;
    }

    /** The shared window backing this view. */
    public AllvrRenderYWindow shared() {
        return this.window;
    }

    public long epoch() {
        return this.window.epoch();
    }

    public int originBlockY() {
        return this.window.originBlockY();
    }

    /** Virtual cell Y for one ABSOLUTE cell Y at a level (voxy plan §5.1). */
    public int virtualCellY(int level, int absoluteCellY) {
        return absoluteCellY - (this.window.originBlockY() >> (5 + level));
    }

    /** Camera remap for the viewport patch (voxy plan §5.1). */
    public double virtualCameraY(double absoluteCameraY) {
        return this.window.virtualCameraY(absoluteCameraY);
    }

    /** True while the shared window is mid-rebase — the far backend must not
     *  accept new work (plan §6.3 FREEZE). */
    public boolean isRebasing() {
        return this.window.isRebasing();
    }

    /** The origin the shared rebase will publish next (bridge-computed). */
    public static int alignedOrigin(double playerY) {
        return (int) Math.rint(playerY / (double) ALIGN_BLOCKS) * ALIGN_BLOCKS;
    }
}
