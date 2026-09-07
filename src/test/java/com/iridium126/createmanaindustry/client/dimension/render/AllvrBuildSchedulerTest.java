package com.iridium126.createmanaindustry.client.dimension.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class AllvrBuildSchedulerTest {

    @Test
    void workerSettlesSuccessAndFailureWithoutStrandingQueue() throws Exception {
        try (AllvrBuildScheduler<Integer, Integer> scheduler =
                 new AllvrBuildScheduler<>(1, (input, token) -> {
                     if (input < 0) {
                         throw new IllegalStateException("test failure");
                     }
                     return input * 2;
                 })) {
            scheduler.submit(1L, 7L, 3L, AllvrBuildScheduler.Priority.SCREEN_FIRST, 4);
            scheduler.submit(2L, 7L, 1L, AllvrBuildScheduler.Priority.BACKGROUND, -1);
            AllvrBuildScheduler.Result<Integer> first = await(scheduler);
            AllvrBuildScheduler.Result<Integer> second = await(scheduler);
            assertNotNull(first);
            assertNotNull(second);
            assertEquals(AllvrBuildScheduler.Status.SUCCESS, first.status());
            assertEquals(AllvrBuildScheduler.Status.FAILED_RETRYABLE, second.status());
        }
    }

    private static <T> AllvrBuildScheduler.Result<T> await(AllvrBuildScheduler<?, T> scheduler)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        AllvrBuildScheduler.Result<T> result;
        while ((result = scheduler.poll()) == null && System.nanoTime() < deadline) {
            Thread.sleep(2L);
        }
        return result;
    }
}
