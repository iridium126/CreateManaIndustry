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
    public final AtomicLong persistedLoadRequests = new AtomicLong();
    public final AtomicLong persistedLoadFailures = new AtomicLong();
    public final AtomicLong persistedLoadQuarantined = new AtomicLong();
    public final AtomicLong persistedLoadNanos = new AtomicLong();
    /** Physical region read/decompression/NBT parse time, excluding decode-pool work. */
    public final AtomicLong persistedStorageReadNanos = new AtomicLong();
    public final AtomicLong persistedStorageReadCount = new AtomicLong();
    /** Time spent in the ChunkMap-style NBT/codec deserializer pool. */
    public final AtomicLong persistedDecodeNanos = new AtomicLong();
    public final AtomicLong cubesGenerated = new AtomicLong();
    public final AtomicLong snapshotNanos = new AtomicLong();
    public final AtomicLong commitNanos = new AtomicLong();
    public final AtomicLong maxCommitNanos = new AtomicLong();
    public final AtomicLong singleRecordCommits = new AtomicLong();
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
            + " loadRequests=" + this.persistedLoadRequests.get()
            + " loadFailures=" + this.persistedLoadFailures.get()
            + " loadQuarantined=" + this.persistedLoadQuarantined.get()
            + " loadMs=" + (this.persistedLoadNanos.get() / 1_000_000L)
            + " readMs=" + (this.persistedStorageReadNanos.get() / 1_000_000L)
            + " reads=" + this.persistedStorageReadCount.get()
            + " decodeMs=" + (this.persistedDecodeNanos.get() / 1_000_000L)
            + " generated=" + this.cubesGenerated.get()
            + " snapshotMs=" + (this.snapshotNanos.get() / 1_000_000L)
            + " commitMs=" + (this.commitNanos.get() / 1_000_000L)
            + " maxCommitMs=" + (this.maxCommitNanos.get() / 1_000_000L)
            + " singleRecordCommits=" + this.singleRecordCommits.get()
            + " lastSaveMs=" + this.lastSaveDurationMs
            + " lastFlushMs=" + this.lastFlushDurationMs
            + " lastIoError=" + this.lastIoError;
    }
}
