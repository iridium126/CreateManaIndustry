package com.iridium126.createmanaindustry.client.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class YsmPreviewRequestTimerTest {
    @Test
    void coalescesRequestsAndRetriesAfterTimeoutAndTransientFailure() {
        var timer = new YsmPreviewRequestTimer();
        assertTrue(timer.shouldRequest(1_000));
        timer.requested(1_000);
        assertFalse(timer.shouldRequest(10_999));
        assertFalse(timer.shouldRequest(11_000));
        assertFalse(timer.shouldRequest(11_999));
        assertTrue(timer.shouldRequest(12_000));

        timer.requested(12_000);
        timer.retryAfter(12_500, 2_000);
        assertFalse(timer.shouldRequest(14_499));
        assertTrue(timer.shouldRequest(14_500));
        timer.requested(14_500);
        timer.received();
        assertTrue(timer.shouldRequest(14_500), "a successful response clears retry state");
    }
}
