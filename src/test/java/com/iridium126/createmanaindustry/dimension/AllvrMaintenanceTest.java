package com.iridium126.createmanaindustry.dimension;

import com.iridium126.createmanaindustry.dimension.cube.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

class AllvrMaintenanceTest {
    @Test void stationaryWatchRetriesWithoutMovementAndCancelsAbandonedKeys() {
        var retries = new AllvrRetryQueue(); retries.failed(5, 0);
        assertTrue(retries.poll(19, 16).isEmpty());
        assertEquals(List.of(5L), retries.poll(20, 16));
        retries.failed(5, 20);
        assertTrue(retries.poll(59, 16).isEmpty());
        assertEquals(List.of(5L), retries.poll(60, 16));
        retries.failed(5, 60); retries.retain(key -> false);
        assertEquals(0, retries.size()); assertTrue(retries.poll(1000, 16).isEmpty());
    }
    @Test void emptyWorldReleasesCleanCubesButPinsFailedSnapshots() {
        var queue = new AllvrUnloadQueue(); queue.request(new long[]{1, 2});
        var removed = new HashSet<Long>();
        for (int time = 0; time < 20; time++) queue.tick(time, key -> false, key -> {
            if (key == 1) { removed.add(key); return -1; } return 200;
        });
        assertEquals(Set.of(1L), removed); assertEquals(1, queue.pending());
        queue.tick(220, key -> false, key -> -1);
        assertEquals(0, queue.pending());
    }
}
