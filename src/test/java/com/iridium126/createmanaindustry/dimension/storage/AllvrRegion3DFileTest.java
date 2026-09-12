package com.iridium126.createmanaindustry.dimension.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 (plan §10.1/§10.2): the shadow-paging region file — round trips, batch
 * commits, sector reuse, and every fault-injection point: torn header,
 * payload-written-but-header-not-committed, and both-headers-invalid.
 */
class AllvrRegion3DFileTest {

    @TempDir
    Path temp;

    private static byte[] record(String s) {
        return ("rec:" + s).getBytes(StandardCharsets.UTF_8);
    }

    private static Path file() {
        return Path.of("r.0.0.0.3dr");
    }

    @Test
    void freshFileHasNoRecords() throws IOException {
        try (AllvrRegion3DFile file = AllvrRegion3DFile.open(this.temp.resolve(file()), 0, 0, 0)) {
            assertFalse(file.has(0));
            assertFalse(file.has(4095));
            assertEquals(1, file.generation());
        }
    }

    @Test
    void batchCommitRoundTrip() throws IOException {
        Path path = this.temp.resolve(file());
        Int2ObjectOpenHashMap<byte[]> batch = new Int2ObjectOpenHashMap<>();
        batch.put(0, record("hello"));
        batch.put(4095, record("world"));
        batch.put(257, new byte[AllvrStorageFormat.SECTOR_BYTES * 3 + 7]); // multi-sector record
        try (AllvrRegion3DFile file = AllvrRegion3DFile.open(path, 0, 0, 0)) {
            file.commit(batch);
            assertArrayEquals(record("hello"), file.read(0));
            assertArrayEquals(record("world"), file.read(4095));
            assertEquals(AllvrStorageFormat.SECTOR_BYTES * 3 + 7, file.read(257).length);
            assertFalse(file.has(1));
        }
        // survives reopen
        try (AllvrRegion3DFile file = AllvrRegion3DFile.open(path, 0, 0, 0)) {
            assertArrayEquals(record("hello"), file.read(0));
            assertArrayEquals(record("world"), file.read(4095));
        }
    }

    @Test
    void replacementReusesFreedSectors() throws IOException {
        Path path = this.temp.resolve(file());
        Int2ObjectOpenHashMap<byte[]> v1 = new Int2ObjectOpenHashMap<>();
        v1.put(0, record("first"));
        Int2ObjectOpenHashMap<byte[]> v2 = new Int2ObjectOpenHashMap<>();
        v2.put(0, record("second"));
        Int2ObjectOpenHashMap<byte[]> v3 = new Int2ObjectOpenHashMap<>();
        v3.put(0, new byte[AllvrStorageFormat.SECTOR_BYTES * 2]);
        Int2ObjectOpenHashMap<byte[]> v4 = new Int2ObjectOpenHashMap<>();
        v4.put(0, new byte[AllvrStorageFormat.SECTOR_BYTES * 2]);
        try (AllvrRegion3DFile file = AllvrRegion3DFile.open(path, 0, 0, 0)) {
            file.commit(v1);
            long s1 = Files.size(path);
            // the replaced sectors are freed only AFTER the header commit (crash
            // safety), so the directly following commit may append — but the
            // merged free run must be reused by the commit after that
            file.commit(v2);
            long s2 = Files.size(path);
            assertTrue(s2 > s1);
            assertArrayEquals(v2.get(0), file.read(0));
            file.commit(v3);
            long s3 = Files.size(path);
            assertTrue(s3 > s2, "2-sector record must extend the file here");
            file.commit(v4);
            assertEquals(s3, Files.size(path), "merged free run was not reused");
            assertArrayEquals(v4.get(0), file.read(0));
        }
    }

    @Test
    void tornHeaderRecoversPreviousGeneration() throws IOException {
        // commit v1 (header B becomes active), commit v2 (header A becomes
        // active), then tear header A mid-write: the previous generation (v1)
        // must still be readable
        Path path = this.temp.resolve(file());
        Int2ObjectOpenHashMap<byte[]> v1 = new Int2ObjectOpenHashMap<>();
        v1.put(0, record("one"));
        Int2ObjectOpenHashMap<byte[]> v2 = new Int2ObjectOpenHashMap<>();
        v2.put(0, record("two"));
        try (AllvrRegion3DFile file = AllvrRegion3DFile.open(path, 0, 0, 0)) {
            file.commit(v1);
            file.commit(v2);
        }
        // simulate a torn header write on the active copy A
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer garbage = ByteBuffer.allocate(AllvrStorageFormat.HEADER_SECTORS * AllvrStorageFormat.SECTOR_BYTES);
            byte[] bytes = garbage.array();
            for (int i = 0; i < 1000; i++) {
                bytes[i] = 0x5A;
            }
            while (garbage.hasRemaining()) {
                channel.write(garbage, garbage.position());
            }
            channel.force(true);
        }
        try (AllvrRegion3DFile file = AllvrRegion3DFile.open(path, 0, 0, 0)) {
            assertArrayEquals(record("one"), file.read(0));
        }
    }

    @Test
    void payloadWrittenWithoutHeaderCommitKeepsOldRecords() throws IOException {
        // simulate "crashed after payload force, before header force" by
        // rolling the header copies back to the pre-commit image
        Path path = this.temp.resolve(file());
        Int2ObjectOpenHashMap<byte[]> v1 = new Int2ObjectOpenHashMap<>();
        v1.put(0, record("durable"));
        Int2ObjectOpenHashMap<byte[]> v2 = new Int2ObjectOpenHashMap<>();
        v2.put(0, record("lost"));
        try (AllvrRegion3DFile file = AllvrRegion3DFile.open(path, 0, 0, 0)) {
            file.commit(v1);
        }
        long headerBImage = AllvrStorageFormat.HEADER_SECTORS * (long) AllvrStorageFormat.SECTOR_BYTES;
        byte[] previous = new byte[(int) headerBImage];
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.wrap(previous);
            while (buf.hasRemaining()) {
                channel.read(buf, headerBImage + buf.position());
            }
        }
        try (AllvrRegion3DFile file = AllvrRegion3DFile.open(path, 0, 0, 0)) {
            file.commit(v2); // active header is B; commit writes A
            assertEquals(3, file.generation());
        }
        // crash: header A (holding v2) is lost, the old header B (v1) remains
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.wrap(previous);
            while (buf.hasRemaining()) {
                channel.write(buf, buf.position());
            }
            channel.force(true);
        }
        try (AllvrRegion3DFile file = AllvrRegion3DFile.open(path, 0, 0, 0)) {
            assertArrayEquals(record("durable"), file.read(0));
        }
    }

    @Test
    void bothHeadersInvalidFailsClosed() throws IOException {
        Path path = this.temp.resolve(file());
        Int2ObjectOpenHashMap<byte[]> batch = new Int2ObjectOpenHashMap<>();
        batch.put(0, record("x"));
        try (AllvrRegion3DFile file = AllvrRegion3DFile.open(path, 0, 0, 0)) {
            file.commit(batch);
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            byte[] garbage = new byte[AllvrStorageFormat.HEADER_SECTORS * 2 * AllvrStorageFormat.SECTOR_BYTES];
            for (int i = 0; i < garbage.length; i++) {
                garbage[i] = (byte) i;
            }
            ByteBuffer buf = ByteBuffer.wrap(garbage);
            while (buf.hasRemaining()) {
                channel.write(buf, buf.position());
            }
            channel.force(true);
        }
        assertThrows(AllvrCubeCorruptedException.class, () -> AllvrRegion3DFile.open(path, 0, 0, 0));
    }

    @Test
    void slotTableRoundTripsThroughHeader() throws IOException {
        Path path = this.temp.resolve(file());
        try (AllvrRegion3DFile file = AllvrRegion3DFile.open(path, 3, -4, 5)) {
            assertEquals(3, file.regionX());
            assertEquals(-4, file.regionY());
            assertEquals(5, file.regionZ());
        }
        try (AllvrRegion3DFile file = AllvrRegion3DFile.open(path, 3, -4, 5)) {
            // identity mismatch with the requested coordinates must not be
            // silently accepted — reopen with wrong coords fails closed
            assertEquals(3, file.regionX());
        }
        assertThrows(AllvrCubeCorruptedException.class, () -> {
            try (AllvrRegion3DFile wrong = AllvrRegion3DFile.open(path, 9, 9, 9)) {
                // header says r.3.-4.5 — treating it as r.9.9.9 is corruption
            }
        });
    }

    @Test
    void forEachFilledSlotEnumeratesExactly() throws IOException {
        Path path = this.temp.resolve(file());
        Int2ObjectOpenHashMap<byte[]> batch = new Int2ObjectOpenHashMap<>();
        batch.put(0, record("a"));
        batch.put(7, record("b"));
        batch.put(4096 - 1, record("c"));
        try (AllvrRegion3DFile file = AllvrRegion3DFile.open(path, 0, 0, 0)) {
            file.commit(batch);
            AtomicInteger count = new AtomicInteger();
            file.forEachFilledSlot(slot -> {
                assertTrue(batch.containsKey(slot));
                count.incrementAndGet();
            });
            assertEquals(batch.size(), count.get());
        }
    }
}
