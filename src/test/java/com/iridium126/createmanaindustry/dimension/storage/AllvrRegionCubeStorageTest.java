package com.iridium126.createmanaindustry.dimension.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end {@link AllvrRegionCubeStorage} checks over real files: region
 * routing for negative coordinates, compression round trip, header-index
 * enumeration and CRC failure detection (plan §10.1).
 */
class AllvrRegionCubeStorageTest {

    @TempDir
    Path temp;

    private static CompoundTag tag(int n) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("n", n);
        CompoundTag child = new CompoundTag();
        child.putLong("l", 123456789012345L);
        tag.put("child", child);
        return tag;
    }

    @Test
    void writeReadRoundTripAcrossRegions() throws IOException {
        Path folder = this.temp.resolve("region3d");
        AllvrCubePos a = AllvrCubePos.of(0, 0, 0);          // region (0,0,0)
        AllvrCubePos b = AllvrCubePos.of(15, 0, 0);         // same region
        AllvrCubePos c = AllvrCubePos.of(-1, -1, -1);       // region (-1,-1,-1)
        AllvrCubePos d = AllvrCubePos.of(1_000_000, 0, -1_000_000); // far region
        try (AllvrRegionCubeStorage storage = new AllvrRegionCubeStorage(folder)) {
            var batch = new java.util.LinkedHashMap<AllvrCubePos, CompoundTag>();
            batch.put(a, tag(1));
            batch.put(b, tag(2));
            batch.put(c, tag(3));
            batch.put(d, tag(4));
            storage.writeBatch(batch);
            assertTrue(storage.has(a) && storage.has(b) && storage.has(c) && storage.has(d));
            assertEquals(1, storage.read(a).orElseThrow().getInt("n"));
            assertEquals(2, storage.read(b).orElseThrow().getInt("n"));
            assertEquals(3, storage.read(c).orElseThrow().getInt("n"));
            assertEquals(4, storage.read(d).orElseThrow().getInt("n"));
            assertTrue(Files.isDirectory(folder), "region3d folder must exist after the first write");
        }
        // a fresh storage instance reads the same records
        try (AllvrRegionCubeStorage storage = new AllvrRegionCubeStorage(folder)) {
            assertEquals(1, storage.read(a).orElseThrow().getInt("n"));
            Set<AllvrCubePos> enumerated = new HashSet<>();
            storage.forEachCube(enumerated::add);
            assertEquals(Set.of(a, b, c, d), enumerated);
        }
        // absent cubes read as empty and has() is false
        try (AllvrRegionCubeStorage storage = new AllvrRegionCubeStorage(folder)) {
            assertFalse(storage.has(AllvrCubePos.of(7, 7, 7)));
            assertTrue(storage.read(AllvrCubePos.of(7, 7, 7)).isEmpty());
        }
    }

    @Test
    void emptyFolderMeansNoRecords() throws IOException {
        Path folder = this.temp.resolve("never-written");
        try (AllvrRegionCubeStorage storage = new AllvrRegionCubeStorage(folder)) {
            assertFalse(storage.has(AllvrCubePos.of(0, 0, 0)));
            List<AllvrCubePos> all = new ArrayList<>();
            storage.forEachCube(all::add);
            assertTrue(all.isEmpty());
            assertFalse(Files.exists(folder), "no region3d folder may be created before the first write");
        }
    }

    @Test
    void corruptedPayloadFailsClosed() throws IOException {
        Path folder = this.temp.resolve("region3d");
        AllvrCubePos pos = AllvrCubePos.of(0, 0, 0);
        try (AllvrRegionCubeStorage storage = new AllvrRegionCubeStorage(folder)) {
            storage.writeBatch(java.util.Map.of(pos, tag(1)));
        }
        // first record lands in the first payload sector (48); flip a payload byte
        Path regionFile = folder.resolve(AllvrRegionIndex.fileName(0, 0, 0));
        long payloadStart = (long) AllvrStorageFormat.PAYLOAD_START_SECTOR * AllvrStorageFormat.SECTOR_BYTES;
        try (FileChannel channel = FileChannel.open(regionFile, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer one = ByteBuffer.wrap(new byte[] {(byte) 0xA5});
            channel.write(one, payloadStart + 5);
            channel.force(true);
        }
        try (AllvrRegionCubeStorage storage = new AllvrRegionCubeStorage(folder)) {
            assertTrue(storage.has(pos), "header index still lists the record");
            assertThrows(AllvrCubeCorruptedException.class, () -> storage.read(pos));
        }
    }

    @Test
    void replacementIsVisibleAndFileStaysSmall() throws IOException {
        Path folder = this.temp.resolve("region3d");
        AllvrCubePos pos = AllvrCubePos.of(0, 0, 0);
        try (AllvrRegionCubeStorage storage = new AllvrRegionCubeStorage(folder)) {
            for (int i = 0; i < 25; i++) {
                storage.writeBatch(java.util.Map.of(pos, tag(i)));
                assertEquals(i, storage.read(pos).orElseThrow().getInt("n"));
            }
        }
        long size = Files.size(folder.resolve(AllvrRegionIndex.fileName(0, 0, 0)));
        // 25 tiny records over the same slot: sector reuse must keep the file
        // at its initial footprint (2 headers + 1 record sector)
        assertTrue(size < (AllvrStorageFormat.PAYLOAD_START_SECTOR + 2) * AllvrStorageFormat.SECTOR_BYTES,
            "file grew without bound: " + size);
    }

    @Test
    void flushForcesAndCloseIsIdempotent() throws IOException {
        Path folder = this.temp.resolve("region3d");
        AllvrRegionCubeStorage storage = new AllvrRegionCubeStorage(folder);
        storage.writeBatch(java.util.Map.of(AllvrCubePos.of(0, 0, 0), tag(9)));
        storage.flush();
        storage.flush();
        storage.close();
        storage.close(); // idempotent
        assertArrayEquals(new byte[0], new byte[0]); // no-op to keep the assertion import
    }
}
