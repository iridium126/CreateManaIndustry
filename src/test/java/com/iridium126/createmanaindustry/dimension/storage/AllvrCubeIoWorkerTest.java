package com.iridium126.createmanaindustry.dimension.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import org.junit.jupiter.api.Test;

import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3 (plan §8/§10.1): the IO worker — latest-wins, read-your-writes,
 * conditional removal of only the exact written snapshot, retry-after-failure
 * and close semantics. Uses an in-memory fake storage so no game bootstrap is
 * needed (CompoundTag is registry-free).
 */
class AllvrCubeIoWorkerTest {

    /** In-memory stand-in for region3d; optional failure/gate hooks. */
    private static final class FakeStorage implements AllvrCubeStorage {
        final Map<AllvrCubePos, CompoundTag> disk = new HashMap<>();
        final AtomicInteger writeCalls = new AtomicInteger();
        Runnable beforeWrite; // hook inside writeBatch (gate / failure)
        boolean closed;
        boolean flushed;

        private static byte[] toBytes(CompoundTag tag) throws IOException {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            NbtIo.write(tag, new DataOutputStream(raw));
            return raw.toByteArray();
        }

        private static CompoundTag fromBytes(byte[] bytes) throws IOException {
            return NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)), NbtAccounter.unlimitedHeap());
        }

        @Override
        public boolean has(AllvrCubePos pos) {
            return this.disk.containsKey(pos);
        }

        @Override
        public Optional<CompoundTag> read(AllvrCubePos pos) throws IOException {
            CompoundTag tag = this.disk.get(pos);
            return tag == null ? Optional.empty() : Optional.of(fromBytes(toBytes(tag)));
        }

        @Override
        public void writeBatch(Map<AllvrCubePos, CompoundTag> cubes) throws IOException {
            this.writeCalls.incrementAndGet();
            if (this.beforeWrite != null) {
                this.beforeWrite.run();
            }
            for (Map.Entry<AllvrCubePos, CompoundTag> e : cubes.entrySet()) {
                // round trip through bytes like the real storage so callers
                // can never rely on tag identity across the boundary
                this.disk.put(e.getKey(), fromBytes(toBytes(e.getValue())));
            }
        }

        @Override
        public void forEachCube(Consumer<AllvrCubePos> consumer) {
            for (AllvrCubePos pos : this.disk.keySet().toArray(new AllvrCubePos[0])) {
                consumer.accept(pos);
            }
        }

        @Override
        public void flush() {
            this.flushed = true;
        }

        @Override
        public void close() {
            this.closed = true;
        }
    }

    private static AllvrCubeSnapshot snapshot(String name, int version) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("v", version);
        tag.putString("name", name);
        return new AllvrCubeSnapshot(AllvrCubePos.of(0, 0, 0), version, tag);
    }

    private static void awaitPendingEmpty(AllvrCubeIoWorker worker) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (worker.pendingCount() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(0, worker.pendingCount(), "pending did not drain in time");
    }

    @Test
    void latestWinsABC() throws Exception {
        FakeStorage storage = new FakeStorage();
        AllvrCubeIoWorker worker = new AllvrCubeIoWorker(storage, new AllvrStorageDiagnostics());
        try {
            worker.enqueue(snapshot("A", 1));
            worker.enqueue(snapshot("B", 2));
            worker.enqueue(snapshot("C", 3));
            awaitPendingEmpty(worker);
            assertEquals(3, worker.loadBlocking(AllvrCubePos.of(0, 0, 0)).orElseThrow().getInt("v"));
            assertEquals(3, storage.disk.get(AllvrCubePos.of(0, 0, 0)).getInt("v"));
            assertFalse(storage.flushed); // background drains do not force
        } finally {
            worker.close();
        }
    }

    @Test
    void readYourWritesBeforeDisk() throws Exception {
        FakeStorage storage = new FakeStorage();
        CountDownLatch gate = new CountDownLatch(1);
        storage.beforeWrite = () -> {
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        AllvrCubeIoWorker worker = new AllvrCubeIoWorker(storage, new AllvrStorageDiagnostics());
        try {
            worker.enqueue(snapshot("A", 1));
            // writeBatch is gated: the record can only come from the pending map
            assertEquals(1, worker.loadBlocking(AllvrCubePos.of(0, 0, 0)).orElseThrow().getInt("v"));
            assertTrue(storage.disk.isEmpty());
            gate.countDown();
            awaitPendingEmpty(worker);
            assertEquals(1, storage.disk.get(AllvrCubePos.of(0, 0, 0)).getInt("v"));
        } finally {
            gate.countDown();
            worker.close();
        }
    }

    @Test
    void snapshotDuringWriteIsNotDeletedByTheOldBatch() throws Exception {
        FakeStorage storage = new FakeStorage();
        CountDownLatch enteredWrite = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        AllvrCubePos pos = AllvrCubePos.of(0, 0, 0);
        storage.beforeWrite = () -> {
            if (storage.writeCalls.get() == 1) { // gate only the C write
                enteredWrite.countDown();
                try {
                    releaseWrite.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        AllvrCubeIoWorker worker = new AllvrCubeIoWorker(storage, new AllvrStorageDiagnostics());
        try {
            worker.enqueue(snapshot("C", 3));
            assertTrue(enteredWrite.await(5, TimeUnit.SECONDS), "write never started");
            // D is enqueued while C's batch is mid-write
            worker.enqueue(snapshot("D", 4));
            releaseWrite.countDown();
            awaitPendingEmpty(worker);
            // the C batch must not have deleted D, and D must win on disk
            assertEquals(4, worker.loadBlocking(pos).orElseThrow().getInt("v"));
            assertEquals(4, storage.disk.get(pos).getInt("v"));
        } finally {
            releaseWrite.countDown();
            worker.close();
        }
    }

    @Test
    void flushIsDurable() throws Exception {
        FakeStorage storage = new FakeStorage();
        AllvrCubeIoWorker worker = new AllvrCubeIoWorker(storage, new AllvrStorageDiagnostics());
        try {
            worker.enqueue(snapshot("A", 1));
            worker.flush();
            assertTrue(storage.flushed);
            assertEquals(0, worker.pendingCount());
            assertEquals(1, storage.disk.get(AllvrCubePos.of(0, 0, 0)).getInt("v"));
            worker.flush(); // idempotent when nothing pending
        } finally {
            worker.close();
        }
    }

    @Test
    void writeFailureKeepsPendingAndRetries() throws Exception {
        FakeStorage storage = new FakeStorage();
        AtomicInteger failures = new AtomicInteger();
        AllvrCubeIoWorker worker = new AllvrCubeIoWorker(storage, new AllvrStorageDiagnostics());
        try {
            storage.beforeWrite = () -> {
                if (failures.incrementAndGet() <= 2) {
                    throw new RuntimeException(new IOException("disk on fire"));
                }
            };
            worker.enqueue(snapshot("A", 1));
            // flush retries up to the cap; the third attempt succeeds
            worker.flush();
            assertEquals(1, storage.disk.get(AllvrCubePos.of(0, 0, 0)).getInt("v"));
            assertEquals(0, worker.pendingCount());
        } finally {
            worker.close();
        }
    }

    @Test
    void flushFailsClosedAfterRetryCap() throws Exception {
        FakeStorage storage = new FakeStorage();
        AllvrCubeIoWorker worker = new AllvrCubeIoWorker(storage, new AllvrStorageDiagnostics());
        try {
            storage.beforeWrite = () -> {
                throw new RuntimeException(new IOException("permanently broken"));
            };
            worker.enqueue(snapshot("A", 1));
            assertThrows(IOException.class, worker::flush);
            assertEquals(1, worker.pendingCount(), "failed writes must stay pending");
        } finally {
            worker.close();
        }
    }

    @Test
    void closeIsIdempotentAndRejectsSubmissions() throws Exception {
        FakeStorage storage = new FakeStorage();
        AllvrCubeIoWorker worker = new AllvrCubeIoWorker(storage, new AllvrStorageDiagnostics());
        worker.enqueue(snapshot("A", 1));
        worker.close();
        worker.close(); // idempotent
        assertTrue(storage.closed);
        assertThrows(IllegalStateException.class, () -> worker.enqueue(snapshot("B", 2)));
        assertThrows(IllegalStateException.class, () -> worker.loadBlocking(AllvrCubePos.of(1, 1, 1)));
        assertThrows(IllegalStateException.class, worker::flush);
        // pending was drained by close
        assertEquals(0, worker.pendingCount());
        assertEquals(1, storage.disk.get(AllvrCubePos.of(0, 0, 0)).getInt("v"));
    }

    @Test
    void persistedKeysUnionsPendingAndDisk() throws Exception {
        FakeStorage storage = new FakeStorage();
        storage.disk.put(AllvrCubePos.of(10, 0, 0), new CompoundTag());
        AllvrCubeIoWorker worker = new AllvrCubeIoWorker(storage, new AllvrStorageDiagnostics());
        try {
            worker.enqueue(new AllvrCubeSnapshot(AllvrCubePos.of(20, 0, 0), 1, new CompoundTag()));
            // pending entry is visible without waiting for the drain
            var keys = new java.util.HashSet<>(worker.persistedKeysSnapshot());
            assertTrue(keys.contains(AllvrCubePos.of(10, 0, 0).asLong()));
            assertTrue(keys.contains(AllvrCubePos.of(20, 0, 0).asLong()));
        } finally {
            worker.close();
        }
    }
}
