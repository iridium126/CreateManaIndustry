package com.iridium126.createmanaindustry.dimension.storage;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import net.minecraft.nbt.CompoundTag;

import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;

/**
 * Raw cube-record storage behind the IO worker (plan §7.1): byte/block-level
 * durability only — no live cube objects ever cross this interface, and it
 * carries no pending-write knowledge (read-your-writes is the worker's job).
 * <p>
 * Implementations may opt into parallel read tasks. Such reads must be
 * safe while no batch mutation is in progress; batch writes remain ordered
 * per region (one header commit per batch). The initial header enumeration
 * still runs through the worker before any concurrent task exists.
 */
public interface AllvrCubeStorage extends AutoCloseable {

    /** Whether a record for the cube exists (region header index only, no payload read). */
    boolean has(AllvrCubePos pos) throws IOException;

    /**
     * Reads and decodes one record; empty when no record exists. Corrupt
     * payloads throw {@link AllvrCubeCorruptedException}.
     */
    Optional<CompoundTag> read(AllvrCubePos pos) throws IOException;

    /**
     * Whether {@link #read(AllvrCubePos)} is safe to call concurrently with
     * other reads. The default keeps small test/storage implementations on
     * the original single-worker path; region3d opts in after protecting its
     * file handles with a read/write lock.
     */
    default boolean supportsConcurrentReads() {
        return false;
    }

    /**
     * Atomically commits every given record (per region). Failures leave the
     * previous records readable — the caller retries the whole batch.
     */
    void writeBatch(Map<AllvrCubePos, CompoundTag> cubes) throws IOException;

    /** Enumerates every persisted cube from the region header indexes (no payload reads). */
    void forEachCube(Consumer<AllvrCubePos> consumer) throws IOException;

    /** Forces all open region files to disk. */
    void flush() throws IOException;

    /**
     * Cumulative compressed payload bytes written so far, or {@code -1} when
     * the implementation does not track them (diagnostics only).
     */
    default long bytesWritten() {
        return -1;
    }

    @Override
    void close() throws IOException;
}
