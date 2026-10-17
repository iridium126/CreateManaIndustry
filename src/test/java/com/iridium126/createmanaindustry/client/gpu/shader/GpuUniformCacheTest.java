package com.iridium126.createmanaindustry.client.gpu.shader;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GpuUniformCacheTest {
    @Test void cachesAbsentUniformsAndRetiresReusedProgramNames() {
        var queries = new AtomicInteger();
        var cache = new GpuUniformCache((program, name) -> {
            queries.incrementAndGet();
            return name.equals("missing") ? -1 : queries.get();
        });
        assertEquals(-1, cache.location(7, "missing"));
        assertEquals(-1, cache.location(7, "missing"));
        assertEquals(1, queries.get());
        int before = cache.location(7, "matrix");
        assertEquals(before, cache.location(7, "matrix"));
        cache.retire(7);
        assertNotEquals(before, cache.location(7, "matrix"));
        cache.location(8, "matrix");
        cache.clear();
        cache.location(8, "matrix");
        assertEquals(5, queries.get());
        assertThrows(IllegalArgumentException.class, () -> cache.location(0, "matrix"));
    }
}
