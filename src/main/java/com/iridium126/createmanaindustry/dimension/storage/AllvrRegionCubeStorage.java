package com.iridium126.createmanaindustry.dimension.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;

/**
 * {@code region3d/} implementation of {@link AllvrCubeStorage} (plan §5):
 * routes cube coordinates to 16³-cube region files via
 * {@link AllvrRegionIndex}, compresses records with deflate, and keeps an
 * access-order LRU of at most {@link #MAX_OPEN_REGIONS} region handles
 * (plan §5.3.7).
 * <p>
 * Reads may run concurrently on the bounded read pool. Region-file map/LRU
 * mutations and every commit/flush/close take the write side of a lock, so a
 * reader always sees one complete header image and cannot race a shadow-page
 * commit. The folder is created lazily on the first write — a world whose
 * allay dimension was never saved gains no {@code region3d} directory at all.
 */
public final class AllvrRegionCubeStorage implements AllvrCubeStorage {

    private static final int MAX_OPEN_REGIONS = 64;
    /** Compression algorithm id written as the first record byte (plan P0 constant). */
    static final int COMPRESSION_DEFLATE = 1;
    /** Maximum size of one decoded cube record (vanilla uses 48 MiB); the accounter is per read. */
    private static final long NBT_LIMIT_BYTES = 48L * 1024L * 1024L;

    private final Path folder;
    /** Fairness prevents a continuous foreground read stream starving saves. */
    private final ReentrantReadWriteLock accessLock = new ReentrantReadWriteLock(true);
    /** Access-order LRU; the eldest handle is closed when the cap is exceeded. */
    private final LinkedHashMap<Long, AllvrRegion3DFile> openFiles = new LinkedHashMap<>(64, 0.75f, true);
    private long bytesWritten;
    private final Deflater deflater = new Deflater(Deflater.BEST_SPEED, false);
    /** Reused per-read so parallel region reads never share inflater state. */
    private final ConcurrentLinkedQueue<Inflater> inflaterPool = new ConcurrentLinkedQueue<>();

    public AllvrRegionCubeStorage(Path folder) {
        this.folder = folder;
    }

    // ------------------------------------------------------------------
    // routing
    // ------------------------------------------------------------------

    private static long regionKey(AllvrCubePos pos) {
        return regionKey(AllvrRegionIndex.region(pos.getX()),
            AllvrRegionIndex.region(pos.getY()), AllvrRegionIndex.region(pos.getZ()));
    }

    private static long regionKey(int rx, int ry, int rz) {
        return AllvrRegionIndex.regionKey(rx, ry, rz);
    }

    private static int slot(AllvrCubePos pos) {
        return AllvrRegionIndex.slot(pos);
    }

    private synchronized AllvrRegion3DFile locate(long key, boolean createIfMissing) throws IOException {
        AllvrRegion3DFile file = this.openFiles.get(key);
        if (file != null) {
            return file;
        }
        int rx = AllvrRegionIndex.regionKeyX(key);
        int ry = AllvrRegionIndex.regionKeyY(key);
        int rz = AllvrRegionIndex.regionKeyZ(key);
        Path path = this.folder.resolve(AllvrRegionIndex.fileName(rx, ry, rz));
        if (!createIfMissing && !Files.exists(path)) {
            return null;
        }
        file = AllvrRegion3DFile.open(path, rx, ry, rz);
        this.openFiles.put(key, file);
        this.evictIfNeeded();
        return file;
    }

    // ------------------------------------------------------------------
    // AllvrCubeStorage
    // ------------------------------------------------------------------

    @Override
    public boolean has(AllvrCubePos pos) throws IOException {
        this.accessLock.readLock().lock();
        try {
            AllvrRegion3DFile file = this.locate(regionKey(pos), false);
            return file != null && file.has(slot(pos));
        } finally {
            this.accessLock.readLock().unlock();
        }
    }

    @Override
    public Optional<CompoundTag> read(AllvrCubePos pos) throws IOException {
        byte[] record;
        this.accessLock.readLock().lock();
        try {
            AllvrRegion3DFile file = this.locate(regionKey(pos), false);
            if (file == null || !file.has(slot(pos))) {
                return Optional.empty();
            }
            // Keep the lock around the indexed channel read only. Deflate and
            // NBT parsing are CPU work and can proceed after a writer has
            // received a consistent record snapshot.
            record = file.read(slot(pos));
        } finally {
            this.accessLock.readLock().unlock();
        }
        return Optional.of(this.decompress(record));
    }

    @Override
    public boolean supportsConcurrentReads() {
        return true;
    }

    @Override
    public void writeBatch(Map<AllvrCubePos, CompoundTag> cubes) throws IOException {
        if (cubes.isEmpty()) {
            return;
        }
        this.accessLock.writeLock().lock();
        try {
            Files.createDirectories(this.folder);
            Long2ObjectMap<Int2ObjectMap<byte[]>> perRegion = new Long2ObjectOpenHashMap<>();
            for (Map.Entry<AllvrCubePos, CompoundTag> entry : cubes.entrySet()) {
                AllvrCubePos pos = entry.getKey();
                byte[] record = this.compress(entry.getValue());
                this.bytesWritten += record.length;
                perRegion.computeIfAbsent(regionKey(pos), k -> new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>())
                    .put(slot(pos), record);
            }
            for (Long2ObjectMap.Entry<Int2ObjectMap<byte[]>> region : perRegion.long2ObjectEntrySet()) {
                AllvrRegion3DFile file = this.locate(region.getLongKey(), true);
                file.commit(region.getValue());
            }
        } finally {
            this.accessLock.writeLock().unlock();
        }
    }

    private void evictIfNeeded() throws IOException {
        // A read lock allows several readers to run together. Do not close
        // an LRU handle while one of those readers may still be using it;
        // the next exclusive write/flush pass will trim the cache safely.
        if (this.accessLock.getReadLockCount() > 0 && !this.accessLock.isWriteLocked()) {
            return;
        }
        while (this.openFiles.size() > MAX_OPEN_REGIONS) {
            Iterator<Map.Entry<Long, AllvrRegion3DFile>> it = this.openFiles.entrySet().iterator();
            Map.Entry<Long, AllvrRegion3DFile> eldest = it.next();
            it.remove();
            eldest.getValue().close();
        }
    }

    @Override
    public void forEachCube(Consumer<AllvrCubePos> consumer) throws IOException {
        this.accessLock.writeLock().lock();
        try {
            if (!Files.isDirectory(this.folder)) {
                return;
            }
            List<Path> files;
            try (var stream = Files.newDirectoryStream(this.folder, "*.3dr")) {
                files = new ArrayList<>();
                stream.forEach(files::add);
            }
            for (Path path : files) {
                int[] coords = AllvrRegionIndex.parseFileName(path.getFileName().toString());
                if (coords == null) {
                    continue; // foreign files are ignored, not corruption
                }
                int rx = coords[0];
                int ry = coords[1];
                int rz = coords[2];
                // enumeration uses its own short-lived handle — it must not evict
                // the LRU's working set
                try (AllvrRegion3DFile file = AllvrRegion3DFile.open(path, rx, ry, rz)) {
                    int baseX = rx * AllvrRegionIndex.DIAMETER;
                    int baseY = ry * AllvrRegionIndex.DIAMETER;
                    int baseZ = rz * AllvrRegionIndex.DIAMETER;
                    file.forEachFilledSlot(slot -> consumer.accept(AllvrCubePos.of(
                        baseX + (slot & 15),
                        baseY + (slot >> 8),
                        baseZ + ((slot >> 4) & 15))));
                }
            }
        } finally {
            this.accessLock.writeLock().unlock();
        }
    }

    @Override
    public void flush() throws IOException {
        this.accessLock.writeLock().lock();
        try {
            for (AllvrRegion3DFile file : this.openFiles.values()) {
                file.flush();
            }
        } finally {
            this.accessLock.writeLock().unlock();
        }
    }

    @Override
    public long bytesWritten() {
        return this.bytesWritten;
    }

    @Override
    public void close() throws IOException {
        this.accessLock.writeLock().lock();
        try {
            IOException failure = null;
            for (AllvrRegion3DFile file : this.openFiles.values()) {
                try {
                    file.close();
                } catch (IOException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            this.openFiles.clear();
            this.deflater.end();
            Inflater inflater;
            while ((inflater = this.inflaterPool.poll()) != null) {
                inflater.end();
            }
            if (failure != null) {
                throw failure;
            }
        } finally {
            this.accessLock.writeLock().unlock();
        }
    }

    // ------------------------------------------------------------------
    // record codec: [algo byte][deflate(NBT)]
    // ------------------------------------------------------------------

    private byte[] compress(CompoundTag tag) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(2048);
        NbtIo.write(tag, new DataOutputStream(raw));
        byte[] nbt = raw.toByteArray();
        this.deflater.reset();
        this.deflater.setInput(nbt);
        this.deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 + nbt.length / 2);
        byte[] buf = new byte[8192];
        while (!this.deflater.finished()) {
            int n = this.deflater.deflate(buf);
            out.write(buf, 0, n);
        }
        byte[] compressed = out.toByteArray();
        ByteBuffer record = ByteBuffer.allocate(1 + compressed.length);
        record.put((byte) COMPRESSION_DEFLATE);
        record.put(compressed);
        return record.array();
    }

    private CompoundTag decompress(byte[] record) throws IOException {
        if (record.length < 2 || (record[0] & 0xFF) != COMPRESSION_DEFLATE) {
            throw new AllvrCubeCorruptedException("unknown compression id "
                + (record.length > 0 ? (record[0] & 0xFF) : -1) + " in region3d record");
        }
        Inflater inflater = this.inflaterPool.poll();
        if (inflater == null) {
            inflater = new Inflater();
        }
        inflater.reset();
        inflater.setInput(record, 1, record.length - 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(2048, record.length * 4));
        byte[] buf = new byte[8192];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n > 0) {
                    out.write(buf, 0, n);
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    throw new AllvrCubeCorruptedException("truncated deflate stream in region3d record");
                }
            }
        } catch (DataFormatException e) {
            throw new AllvrCubeCorruptedException("corrupt deflate stream in region3d record", e);
        } finally {
            inflater.reset();
            this.inflaterPool.offer(inflater);
        }
        // NbtAccounter is stateful: reusing one instance across cube reads
        // turns the limit into a process-wide cumulative budget and eventually
        // rejects every otherwise valid record after enough loads.
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(out.toByteArray())),
            NbtAccounter.create(NBT_LIMIT_BYTES));
    }
}
