package com.iridium126.createmanaindustry.dimension.storage;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Persistence diagnostics counters (plan §7.3/§11): loaded/dirty/pending
 * counts come from the cube map and worker on demand; the cumulative totals
 * and last failure live here. Read-only introspection — nothing in the save
 * path depends on these values.
 */
public final class AllvrStorageDiagnostics {

    public final AtomicLong snapshotsBuilt = new AtomicLong();
    public final AtomicLong snapshotsFailed = new AtomicLong();
    public final AtomicLong regionCommits = new AtomicLong();
    public final AtomicLong recordsWritten = new AtomicLong();
    public final AtomicLong payloadBytesWritten = new AtomicLong();
    public final AtomicLong cubesLoadedFromDisk = new AtomicLong();
    public final AtomicLong cubesGenerated = new AtomicLong();
    public volatile long lastSaveDurationMs = -1;
    public volatile long lastFlushDurationMs = -1;
    public volatile String lastIoError = "";

    /** Records an I/O failure (rate-limited by the caller). */
    public void noteIoError(String message) {
        this.lastIoError = message;
    }

    @Override
    public String toString() {
        return "snapshots=" + this.snapshotsBuilt.get() + "/" + this.snapshotsFailed.get()
            + " commits=" + this.regionCommits.get()
            + " records=" + this.recordsWritten.get()
            + " bytes=" + this.payloadBytesWritten.get()
            + " loadedFromDisk=" + this.cubesLoadedFromDisk.get()
            + " generated=" + this.cubesGenerated.get()
            + " lastSaveMs=" + this.lastSaveDurationMs
            + " lastFlushMs=" + this.lastFlushDurationMs
            + " lastIoError=" + this.lastIoError;
    }
}
