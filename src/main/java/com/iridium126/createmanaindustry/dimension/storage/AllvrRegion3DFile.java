package com.iridium126.createmanaindustry.dimension.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.zip.CRC32C;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

/**
 * One {@code r.<rx>.<ry>.<rz>.3dr} file — 4096 cube slots behind a
 * double-header shadow paging scheme (plan §5.3, aligned with CubicChunks
 * {@code ShadowPagingRegion} semantics, original implementation).
 * <p>
 * Commit protocol (per batch, single I/O thread):
 * <ol>
 *   <li>allocate contiguous payload sectors for every changed record and
 *       write them;</li>
 *   <li>apply the slot updates onto the <b>inactive</b> header copy with a
 *       bumped generation and its CRC32C, then write it whole;</li>
 *   <li>only now return the replaced sectors to the free list. The file is
 *       forced only by {@link #flush()} or {@link #close()}, like vanilla's
 *       RegionFileStorage background saves.</li>
 * </ol>
 * Any interruption leaves either the old header active (old records readable,
 * new sectors orphaned into the free list on the next open) or the new header
 * complete — a torn header never passes its checksum. Both headers invalid is
 * reported as corruption and fails closed.
 * <p>
 * Threading: the storage wrapper serializes commits/flush/close against
 * reads. Positional record reads may run concurrently, while the mutable
 * header/free-list state remains exclusive to a commit.
 */
public final class AllvrRegion3DFile implements AutoCloseable {

    /** Own logger — this class must load without the Minecraft bootstrap (unit tests). */
    private static final Logger LOGGER = LoggerFactory.getLogger("createmanaindustry/AllvrRegion3DFile");

    private static final int HEADER_BYTES = AllvrStorageFormat.HEADER_SECTORS * AllvrStorageFormat.SECTOR_BYTES;
    private static final int SLOTS = AllvrStorageFormat.SLOTS;
    private static final int SLOT_ENTRY_BYTES = AllvrStorageFormat.SLOT_ENTRY_BYTES;
    private static final int SLOT_TABLE_OFFSET = 32;
    private static final int SLOT_TABLE_BYTES = SLOTS * SLOT_ENTRY_BYTES;

    private final FileChannel channel;
    private final int rx;
    private final int ry;
    private final int rz;

    /** Active slot table (mirrors whichever header copy won the open). */
    private final int[] slotOffset = new int[SLOTS];
    private final int[] slotSectors = new int[SLOTS];
    private final int[] slotLength = new int[SLOTS];
    private final long[] slotCrc = new long[SLOTS];

    private int activeHeader;
    private long generation;
    private final ArrayList<Run> freeRuns = new ArrayList<>();
    private int nextFreeSector = AllvrStorageFormat.PAYLOAD_START_SECTOR;
    /** A background commit has changed the file since the last fsync. */
    private boolean dirty;
    private boolean closed;

    private static final class Run {
        int offset;
        int count;

        Run(int offset, int count) {
            this.offset = offset;
            this.count = count;
        }
    }

    private AllvrRegion3DFile(FileChannel channel, int rx, int ry, int rz) {
        this.channel = channel;
        this.rx = rx;
        this.ry = ry;
        this.rz = rz;
    }

    /**
     * Opens (or creates) the region file and picks the newest valid header.
     *
     * @throws AllvrCubeCorruptedException when the file exists but neither
     *                                     header is readable — fail closed, an admin must repair or remove it
     */
    public static AllvrRegion3DFile open(Path path, int rx, int ry, int rz) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
            StandardOpenOption.WRITE);
        boolean success = false;
        try {
            AllvrRegion3DFile file = new AllvrRegion3DFile(channel, rx, ry, rz);
            file.doOpen(path);
            success = true;
            return file;
        } finally {
            if (!success) {
                channel.close();
            }
        }
    }

    private void doOpen(Path path) throws IOException {
        long size = this.channel.size();
        if (size == 0) {
            // fresh file — write both header copies immediately so any crash
            // afterwards leaves at least one valid header
            this.generation = 1;
            this.activeHeader = 0;
            writeHeaderCopy(1, true);
            this.activeHeader = 1;
            writeHeaderCopy(1, true);
            this.activeHeader = 0;
            this.channel.force(true);
            return;
        }
        HeaderImage a = tryParseHeader(0);
        HeaderImage b = tryParseHeader(1);
        HeaderImage chosen;
        if (a != null && b != null) {
            chosen = a.generation >= b.generation ? a : b;
        } else {
            chosen = a != null ? a : b;
        }
        if (chosen == null) {
            throw new AllvrCubeCorruptedException(
                "region file " + path + " (r." + this.rx + "." + this.ry + "." + this.rz
                    + ") has two invalid headers — refusing to touch it");
        }
        if ((a == null || b == null)) {
            LOGGER.warn("[Allvr] region {} r.{}.{}.{}: header {} invalid, continuing on header {}",
                path, this.rx, this.ry, this.rz, a == null ? "A" : "B", chosen == a ? "A" : "B");
        }
        this.generation = chosen.generation;
        this.activeHeader = chosen.copy;
        System.arraycopy(chosen.slotOffset, 0, this.slotOffset, 0, SLOTS);
        System.arraycopy(chosen.slotSectors, 0, this.slotSectors, 0, SLOTS);
        System.arraycopy(chosen.slotLength, 0, this.slotLength, 0, SLOTS);
        System.arraycopy(chosen.slotCrc, 0, this.slotCrc, 0, SLOTS);
        this.rebuildFreeList(size);
    }

    private void rebuildFreeList(long fileSize) throws AllvrCubeCorruptedException {
        // records are written unpadded, so the file tail can end mid-sector —
        // a partial trailing sector still belongs to its record
        int fileSectors = (int) Math.min(Integer.MAX_VALUE,
            (fileSize + AllvrStorageFormat.SECTOR_BYTES - 1) / AllvrStorageFormat.SECTOR_BYTES);
        this.nextFreeSector = Math.max(AllvrStorageFormat.PAYLOAD_START_SECTOR, fileSectors);
        boolean[] referenced = new boolean[fileSectors];
        for (int slot = 0; slot < SLOTS; slot++) {
            int count = this.slotSectors[slot];
            if (count == 0) {
                continue;
            }
            int offset = this.slotOffset[slot];
            int length = this.slotLength[slot];
            if (offset < AllvrStorageFormat.PAYLOAD_START_SECTOR || offset + count > fileSectors
                || length <= 0 || length > count * AllvrStorageFormat.SECTOR_BYTES) {
                throw new AllvrCubeCorruptedException("region r." + this.rx + "." + this.ry + "." + this.rz
                    + ": slot " + slot + " points outside the file (offset " + offset + ", count " + count + ")");
            }
            for (int s = 0; s < count; s++) {
                referenced[offset + s] = true;
            }
        }
        int runStart = -1;
        for (int s = AllvrStorageFormat.PAYLOAD_START_SECTOR; s <= fileSectors; s++) {
            boolean free = s < fileSectors && !referenced[s];
            if (free && runStart < 0) {
                runStart = s;
            } else if (!free && runStart >= 0) {
                this.freeRuns.add(new Run(runStart, s - runStart));
                runStart = -1;
            }
        }
    }

    // ------------------------------------------------------------------
    // header
    // ------------------------------------------------------------------

    private static final class HeaderImage {
        final int copy;
        final long generation;
        final int[] slotOffset = new int[SLOTS];
        final int[] slotSectors = new int[SLOTS];
        final int[] slotLength = new int[SLOTS];
        final long[] slotCrc = new long[SLOTS];

        HeaderImage(int copy, long generation) {
            this.copy = copy;
            this.generation = generation;
        }
    }

    /** {@code null} when the header image is truncated or fails validation. */
    private HeaderImage tryParseHeader(int copy) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_BYTES);
        long base = (long) copy * AllvrStorageFormat.HEADER_SECTORS * AllvrStorageFormat.SECTOR_BYTES;
        while (buf.hasRemaining()) {
            int read = this.channel.read(buf, base + buf.position());
            if (read < 0) {
                return null; // truncated image
            }
        }
        byte[] image = buf.array();
        CRC32C crc = new CRC32C();
        crc.update(image, 0, HEADER_BYTES - 8);
        long storedCrc = ByteBuffer.wrap(image, HEADER_BYTES - 8, 8).getLong();
        if (crc.getValue() != storedCrc) {
            return null;
        }
        ByteBuffer in = ByteBuffer.wrap(image);
        if (in.getLong() != AllvrStorageFormat.MAGIC
            || in.getInt() != AllvrStorageFormat.REGION_FORMAT_VERSION
            || in.getInt() != this.rx || in.getInt() != this.ry || in.getInt() != this.rz) {
            return null;
        }
        HeaderImage header = new HeaderImage(copy, in.getLong());
        for (int slot = 0; slot < SLOTS; slot++) {
            header.slotOffset[slot] = in.getInt();
            header.slotSectors[slot] = in.getInt();
            header.slotLength[slot] = in.getInt();
            header.slotCrc[slot] = in.getLong();
        }
        return header;
    }

    /**
     * Serializes the current slot tables + a bumped generation onto the
     * inactive header copy and makes it active. The caller chooses whether
     * this header update also forces the channel.
     */
    private void writeHeaderCopy(long newGeneration, boolean force) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_BYTES);
        buf.putLong(AllvrStorageFormat.MAGIC);
        buf.putInt(AllvrStorageFormat.REGION_FORMAT_VERSION);
        buf.putInt(this.rx);
        buf.putInt(this.ry);
        buf.putInt(this.rz);
        buf.putLong(newGeneration);
        for (int slot = 0; slot < SLOTS; slot++) {
            buf.putInt(this.slotOffset[slot]);
            buf.putInt(this.slotSectors[slot]);
            buf.putInt(this.slotLength[slot]);
            buf.putLong(this.slotCrc[slot]);
        }
        buf.position(HEADER_BYTES - 8);
        CRC32C crc = new CRC32C();
        crc.update(buf.array(), 0, HEADER_BYTES - 8);
        buf.putLong(crc.getValue());
        buf.flip();
        long base = (long) this.activeHeader * AllvrStorageFormat.HEADER_SECTORS * AllvrStorageFormat.SECTOR_BYTES;
        while (buf.hasRemaining()) {
            this.channel.write(buf, base + buf.position());
        }
        if (force) {
            this.channel.force(true);
        }
        this.generation = newGeneration;
    }

    // ------------------------------------------------------------------
    // record IO
    // ------------------------------------------------------------------

    /** Raw record bytes for a slot, or {@code null} when the slot is empty. */
    public byte[] read(int slot) throws IOException {
        ensureOpen();
        if (slot < 0 || slot >= SLOTS) {
            throw new IllegalArgumentException("slot " + slot + " outside 0.." + (SLOTS - 1));
        }
        int count = this.slotSectors[slot];
        if (count == 0) {
            return null;
        }
        int length = this.slotLength[slot];
        ByteBuffer buf = ByteBuffer.allocate(length);
        long base = (long) this.slotOffset[slot] * AllvrStorageFormat.SECTOR_BYTES;
        while (buf.hasRemaining()) {
            int read = this.channel.read(buf, base + buf.position());
            if (read < 0) {
                throw new AllvrCubeCorruptedException("region r." + this.rx + "." + this.ry + "." + this.rz
                    + ": slot " + slot + " record truncated on disk");
            }
        }
        CRC32C crc = new CRC32C();
        crc.update(buf.array());
        if (crc.getValue() != this.slotCrc[slot]) {
            throw new AllvrCubeCorruptedException("region r." + this.rx + "." + this.ry + "." + this.rz
                + ": slot " + slot + " record failed its CRC32C check");
        }
        return buf.array();
    }

    public boolean has(int slot) {
        return slot >= 0 && slot < SLOTS && this.slotSectors[slot] > 0;
    }

    /** Invokes the consumer for every filled slot index. */
    public void forEachFilledSlot(java.util.function.IntConsumer consumer) {
        for (int slot = 0; slot < SLOTS; slot++) {
            if (this.slotSectors[slot] > 0) {
                consumer.accept(slot);
            }
        }
    }

    /**
     * Commits a batch of slot → record bytes. All records of one region share
     * a single header commit (plan §5.3.6). On any failure the slot tables
     * stay untouched in memory, so the caller can retry the whole batch.
     */
    public void commit(Int2ObjectMap<byte[]> payloads) throws IOException {
        ensureOpen();
        if (payloads.isEmpty()) {
            return;
        }
        // phase 1: allocate + write payloads (header untouched → crash-safe)
        int size = payloads.size();
        int[] slots = new int[size];
        int[] newOffsets = new int[size];
        int[] newCounts = new int[size];
        int[] oldOffsets = new int[size];
        int[] oldCounts = new int[size];
        int i = 0;
        for (Int2ObjectMap.Entry<byte[]> entry : payloads.int2ObjectEntrySet()) {
            byte[] record = entry.getValue();
            int count = (record.length + AllvrStorageFormat.SECTOR_BYTES - 1) / AllvrStorageFormat.SECTOR_BYTES;
            int offset = this.allocate(count);
            ByteBuffer buf = ByteBuffer.wrap(record);
            long base = (long) offset * AllvrStorageFormat.SECTOR_BYTES;
            while (buf.hasRemaining()) {
                this.channel.write(buf, base + buf.position());
            }
            slots[i] = entry.getIntKey();
            newOffsets[i] = offset;
            newCounts[i] = count;
            oldOffsets[i] = this.slotOffset[slots[i]];
            oldCounts[i] = this.slotSectors[slots[i]];
            i++;
        }
        // phase 2: shadow header commit onto the inactive copy
        int previousActive = this.activeHeader;
        this.activeHeader = 1 - previousActive;
        try {
            for (int e = 0; e < size; e++) {
                int slot = slots[e];
                CRC32C crc = new CRC32C();
                crc.update(payloads.get(slot));
                this.slotOffset[slot] = newOffsets[e];
                this.slotSectors[slot] = newCounts[e];
                this.slotLength[slot] = payloads.get(slot).length;
                this.slotCrc[slot] = crc.getValue();
            }
            writeHeaderCopy(this.generation + 1, false);
        } catch (IOException e) {
            // header commit failed — roll the in-memory tables back to the
            // still-durable old header so a retry starts from a clean state
            this.activeHeader = previousActive;
            throw e;
        }

        // phase 3: release replaced sectors — only after the new header is written
        for (int e = 0; e < size; e++) {
            if (oldCounts[e] > 0) {
                this.free(oldOffsets[e], oldCounts[e]);
            }
        }
        this.dirty = true;
    }

    private int allocate(int count) {
        for (int i = 0; i < this.freeRuns.size(); i++) {
            Run run = this.freeRuns.get(i);
            if (run.count >= count) {
                int offset = run.offset;
                if (run.count == count) {
                    this.freeRuns.remove(i);
                } else {
                    run.offset += count;
                    run.count -= count;
                }
                return offset;
            }
        }
        int offset = this.nextFreeSector;
        this.nextFreeSector += count;
        return offset;
    }

    private void free(int offset, int count) {
        int end = offset + count;
        for (int i = 0; i < this.freeRuns.size(); i++) {
            Run run = this.freeRuns.get(i);
            if (run.offset == end) {
                run.offset = offset;
                run.count += count;
                return;
            }
            if (run.offset + run.count == offset) {
                run.count += count;
                return;
            }
        }
        this.freeRuns.add(new Run(offset, count));
    }

    /** {@code channel.force} — flush() part of the storage interface. */
    public void flush() throws IOException {
        ensureOpen();
        if (this.dirty) {
            this.channel.force(false);
            this.dirty = false;
        }
    }

    public long generation() {
        return this.generation;
    }

    public int regionX() {
        return this.rx;
    }

    public int regionY() {
        return this.ry;
    }

    public int regionZ() {
        return this.rz;
    }

    private void ensureOpen() throws IOException {
        if (this.closed) {
            throw new IOException("region file r." + this.rx + "." + this.ry + "." + this.rz + " is closed");
        }
    }

    @Override
    public void close() throws IOException {
        if (this.closed) {
            return;
        }
        try {
            if (this.dirty) {
                this.channel.force(true);
                this.dirty = false;
            }
        } finally {
            this.closed = true;
            this.channel.close();
        }
    }
}
