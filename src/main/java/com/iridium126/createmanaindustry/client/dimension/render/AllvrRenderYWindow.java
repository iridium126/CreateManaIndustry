package com.iridium126.createmanaindustry.client.dimension.render;

/**
 * The renderer-private virtual Y window shared by the Sodium near-terrain
 * bridge and the Voxy far backend (sodium-parity plan §6). ALLVR's authoritative
 * world spans ±30M block Y, but {@code SectionPos}/{@code BlockPos} and every
 * Sodium section identity must live in a small safe range: the window is a
 * 512-block-aligned {@code originBlockY} tracked near the player and every
 * terrain camera / section key is remapped by {@code -originBlockY}. X/Z are
 * never remapped.
 * <p>
 * Origin ownership: the rebase state machine (STEADY → DETACH → MOVE → REFILL,
 * plan §6.3) is driven by {@link com.iridium126.createmanaindustry.client.dimension.render.sodium.AllvrSodiumBridge}
 * at client-tick boundaries — a tick completes between two rendered frames, so
 * the origin switch is atomic with respect to terrain draws. Both renderers
 * read the same {@code originBlockY}/{@code epoch}; neither may move the origin
 * itself (the Voxy backend observes the epoch and rebuilds its node residency).
 * <p>
 * Because the origin is 512-aligned (a multiple of 16 and 32), section-level
 * and LOD-cell-level virtual coordinates are plain subtraction — no floor
 * correction is ever needed:
 * <pre>
 * virtualBlockY   = absoluteBlockY   - originBlockY
 * virtualSectionY = absoluteSectionY - (originBlockY &gt;&gt; 4)
 * </pre>
 */
public final class AllvrRenderYWindow {

    /** Origin alignment in blocks — shared with the Voxy window (plan §6.3). */
    public static final int ALIGN_BLOCKS = 512;
    /** Rebase trigger distance from the origin (plan §6.3). */
    public static final int REBASE_TRIGGER_BLOCKS = 512;

    /** Rebase phases (plan §6.3). DETACH/REFILL are drained in batches by the
     *  Sodium bridge; MOVE publishes the new origin in one atomic step. */
    public enum Phase {
        /** Normal operation — every resident section maps through the current origin. */
        STEADY,
        /** Old-epoch sections are being removed; new section registrations are refused. */
        DETACH,
        /** New origin is published; sections are re-registered center-out in batches. */
        REFILL
    }

    private volatile long epoch;
    private volatile int originBlockY;
    private volatile Phase phase = Phase.STEADY;

    /** Monotonic window generation — bumped on FREEZE entry and again on MOVE
     *  publish; work stamped with an older epoch must be dropped, not applied. */
    public long epoch() {
        return this.epoch;
    }

    public int originBlockY() {
        return this.originBlockY;
    }

    public Phase phase() {
        return this.phase;
    }

    /** True while the window is mid-rebase (DETACH or REFILL). */
    public boolean isRebasing() {
        return this.phase != Phase.STEADY;
    }

    /** True during the DETACH phase — both renderers must freeze new work. */
    public boolean isDetaching() {
        return this.phase == Phase.DETACH;
    }

    public boolean isSteady() {
        return this.phase == Phase.STEADY;
    }

    public int virtualBlockY(int absoluteBlockY) {
        return absoluteBlockY - this.originBlockY;
    }

    public int absoluteBlockY(int virtualBlockY) {
        return virtualBlockY + this.originBlockY;
    }

    /** Section-Y remap — exact because the origin is 16-aligned. */
    public int virtualSectionY(int absoluteSectionY) {
        return absoluteSectionY - (this.originBlockY >> 4);
    }

    public int absoluteSectionY(int virtualSectionY) {
        return virtualSectionY + (this.originBlockY >> 4);
    }

    /** Terrain-camera remap (plan §6.2): every camera-relative quantity that
     *  pairs with virtual section keys uses the same subtraction. */
    public double virtualCameraY(double absoluteCameraY) {
        return absoluteCameraY - this.originBlockY;
    }

    /** True while the player sits inside the rebase trigger band. */
    public boolean needsRebase(double playerY) {
        if (playerY >= -1024 && playerY < 1024) return this.originBlockY != 0;
        return Math.abs(playerY - this.originBlockY) >= REBASE_TRIGGER_BLOCKS;
    }

    /** The nearest 512-aligned origin for a player Y. */
    public int nextOrigin(double playerY) {
        if (playerY >= -1024 && playerY < 1024) return 0;
        return (int) Math.rint(playerY / (double) ALIGN_BLOCKS) * ALIGN_BLOCKS;
    }

    /**
     * Starts a rebase: bumps the epoch (invalidating all queued/late work) and
     * enters DETACH. No-op when already mid-rebase. The origin itself is NOT
     * moved until {@link #publishMove}.
     */
    public void beginDetach() {
        if (this.phase == Phase.STEADY) {
            this.phase = Phase.DETACH;
            this.epoch++;
        }
    }

    /**
     * MOVE step: publishes the new origin and bumps the epoch again, then
     * enters REFILL. Callers must have detached all old-epoch sections first —
     * old virtual keys must never resurrect.
     */
    public void publishMove(int newOrigin) {
        this.originBlockY = newOrigin;
        this.epoch++;
        this.phase = Phase.REFILL;
    }

    /** Ends a rebase (REFILL complete) — ownership and backlog return to normal. */
    public void endRebase() {
        this.phase = Phase.STEADY;
    }

    /**
     * Starts a fresh level session at an origin chosen from the current
     * camera.  A level can be entered hundreds or thousands of blocks above
     * the vanilla build window, so the first origin must not always be zero.
     * This is intentionally a direct initialization rather than a rebase:
     * there are no sections from the new level that need to be detached yet.
     */
    public void initializeAt(int newOrigin) {
        this.originBlockY = newOrigin;
        this.phase = Phase.STEADY;
        this.epoch++;
    }

    /** Resets the whole window to a fresh session (level load): origin 0,
     *  phase STEADY, epoch continues monotonically so stale work from the
     *  previous session can never alias. */
    public void reset() {
        this.originBlockY = 0;
        this.phase = Phase.STEADY;
        this.epoch++;
    }
}
