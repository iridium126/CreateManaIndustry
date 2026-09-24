package com.iridium126.createmanaindustry.compat.ysm.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class YsmPreviewLimitsTest {
    @Test
    void pngCacheUsesAccessOrderAndEnforcesBothBounds() {
        var cache = new YsmPngCache<String>(2, 4);
        cache.put("a", new byte[] {1, 2});
        cache.put("b", new byte[] {3, 4});
        assertArrayEquals(new byte[] {1, 2}, cache.get("a")); // a becomes most recently used
        cache.put("c", new byte[] {5, 6});
        assertNull(cache.get("b"));
        assertNotNull(cache.get("a"));
        assertNotNull(cache.get("c"));

        cache.put("large", new byte[] {7, 8, 9});
        assertEquals(1, cache.size());
        assertEquals(3, cache.bytes());
        assertNotNull(cache.get("large"));
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(0, cache.bytes());
    }

    @Test
    void identicalRequestsShareWaitersUntilTheRenderCompletes() {
        var waiters = new YsmPreviewWaiters<String>();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), third = UUID.randomUUID();
        assertTrue(waiters.add("same-reference", first));
        assertFalse(waiters.add("same-reference", second));
        waiters.forget(first);
        assertFalse(waiters.add("same-reference", third), "an empty waiter set still represents the active render");
        assertEquals(java.util.List.of(second, third), waiters.take("same-reference").orElseThrow());
        assertTrue(waiters.add("same-reference", first), "a completed render permits a new request");
        waiters.forget(first);
        assertTrue(waiters.take("same-reference").orElseThrow().isEmpty(),
                "an abandoned render remains a known completion so its image can still be cached");
        assertTrue(waiters.take("same-reference").isEmpty(), "completed work is removed");
    }

    @Test
    void workerPoolRunsTwoAndQueuesOnlyThirtyTwoTasks() throws Exception {
        var workers = YsmServerPreviews.createWorkers();
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 2; i++) workers.execute(() -> {
                started.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS), "both render workers should start");
            for (int i = 0; i < 32; i++) workers.execute(() -> {});
            assertEquals(32, workers.getQueue().size());
            assertThrows(RejectedExecutionException.class, () -> workers.execute(() -> {}));
        } finally {
            release.countDown();
            workers.shutdownNow();
            workers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
