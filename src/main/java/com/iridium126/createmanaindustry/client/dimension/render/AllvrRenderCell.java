package com.iridium126.createmanaindustry.client.dimension.render;

/**
 * Main-thread state for one 16³ near-render cell.
 *
 * <p>Revisions are deliberately independent from the GPU allocation.  A
 * worker may finish an old snapshot after the cell was edited; the renderer
 * can then reject that result without exposing an update hole or resurrecting
 * geometry from an earlier world epoch.
 */
public final class AllvrRenderCell {

    public enum BuildState {
        CLEAN,
        QUEUED,
        BUILDING,
        PUBLISHED,
        FAILED
    }

    private final long key;
    private long contentRevision;
    private long scheduledRevision;
    private long publishedRevision;
    private BuildState state = BuildState.CLEAN;
    private int quadStart = -1;
    private int quadCount;
    private int slot = -1;
    private int connectivityMask;

    public AllvrRenderCell(long key) {
        this.key = key;
    }

    public long key() {
        return this.key;
    }

    public long contentRevision() {
        return this.contentRevision;
    }

    public long scheduledRevision() {
        return this.scheduledRevision;
    }

    public long publishedRevision() {
        return this.publishedRevision;
    }

    public BuildState state() {
        return this.state;
    }

    public int quadStart() {
        return this.quadStart;
    }

    public int quadCount() {
        return this.quadCount;
    }

    public int slot() {
        return this.slot;
    }

    public int connectivityMask() {
        return this.connectivityMask;
    }

    /** Marks content dirty.  Repeated edits do not enqueue duplicate work. */
    public long markDirty() {
        this.contentRevision++;
        if (this.state != BuildState.BUILDING) {
            this.state = BuildState.CLEAN;
        }
        return this.contentRevision;
    }

    /** Marks a revision as submitted to the scheduler. */
    public boolean schedule(long revision) {
        if (revision != this.contentRevision || revision <= this.scheduledRevision) {
            return false;
        }
        this.scheduledRevision = revision;
        this.state = BuildState.QUEUED;
        return true;
    }

    public boolean begin(long revision) {
        if (revision != this.contentRevision || revision != this.scheduledRevision) {
            return false;
        }
        this.state = BuildState.BUILDING;
        return true;
    }

    /** Returns false for stale results; stale results must never publish. */
    public boolean publish(long revision, int start, int count) {
        if (revision != this.contentRevision || revision != this.scheduledRevision) {
            return false;
        }
        this.quadStart = start;
        this.quadCount = Math.max(0, count);
        this.publishedRevision = revision;
        this.state = count > 0 ? BuildState.PUBLISHED : BuildState.CLEAN;
        return true;
    }

    /** Keeps the previous GPU mesh while recording a failed build. */
    public boolean fail(long revision) {
        if (revision != this.scheduledRevision) {
            return false;
        }
        this.state = BuildState.FAILED;
        return true;
    }

    public void setAllocation(int start, int count, int slot) {
        this.quadStart = start;
        this.quadCount = Math.max(0, count);
        this.slot = slot;
    }

    public void clearAllocation() {
        this.quadStart = -1;
        this.quadCount = 0;
        this.slot = -1;
        this.publishedRevision = 0;
        this.state = BuildState.CLEAN;
    }

    public void setConnectivityMask(int mask) {
        this.connectivityMask = mask & 0x3F;
    }
}
