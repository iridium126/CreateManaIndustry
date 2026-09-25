package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompilationCacheTest {
    @Test void coldAndProfilePathsNeverSubmitCompilation() {
        try (var cache = new CompilationCache(64, 4, 65536)) {
            for (int i = 0; i < 63; i++) assertNull(cache.acquire(1, CallCompilerTest.fixture(), true));
            for (int i = 0; i < 100; i++) assertNull(cache.acquire(2, CallCompilerTest.fixture(), false));
            assertEquals(0, cache.stats().submitted());
        }
    }

    @Test void hotCodePublishesAndCloseDropsReferences() {
        var cache = new CompilationCache(2, 4, 65536);
        try {
            assertNull(cache.acquire(1, CallCompilerTest.fixture(), true));
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (cache.acquire(1, CallCompilerTest.fixture(), true) == null) Thread.sleep(1);
            });
            assertEquals(1, cache.stats().submitted());
            assertTrue(cache.stats().bytecodeBytes() > 0);
        } finally { cache.close(); }
        assertEquals(0, cache.stats().entries());
        assertEquals(0, cache.stats().bytecodeBytes());
        assertNull(cache.acquire(1, CallCompilerTest.fixture(), true));
    }

    @Test void boundedLruEvictsColdAndPendingSites() {
        try (var cache = new CompilationCache(1, 2, 65536)) {
            for (int i = 0; i < 100; i++) cache.acquire(i, CallCompilerTest.fixture(), true);
            assertEquals(2, cache.stats().entries());
            assertEquals(98, cache.stats().evictions());
        }
    }

    @Test void overBudgetCodeIsDisabledWithoutResubmission() {
        try (var cache = new CompilationCache(1, 4, 1)) {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (cache.stats().failures() == 0) { cache.acquire(1, CallCompilerTest.fixture(), true); Thread.sleep(1); }
            });
            for (int i = 0; i < 100; i++) assertNull(cache.acquire(1, CallCompilerTest.fixture(), true));
            assertEquals(1, cache.stats().submitted());
            assertEquals(0, cache.stats().bytecodeBytes());
        }
    }
}
