package com.iridium126.createmanaindustry.client.render;

/** Retry timing for a client preview request whose response may be lost or transiently unavailable. */
final class YsmPreviewRequestTimer {
    static final long TIMEOUT_MS = 10_000;
    static final long TIMEOUT_RETRY_DELAY_MS = 1_000;

    private boolean requested;
    private long requestedAt;
    private long retryAt;

    boolean shouldRequest(long now) {
        if (requested) {
            if (now - requestedAt < TIMEOUT_MS) return false;
            requested = false;
            retryAt = now + TIMEOUT_RETRY_DELAY_MS;
        }
        return now >= retryAt;
    }

    void requested(long now) {
        requested = true;
        requestedAt = now;
    }

    void received() {
        requested = false;
        requestedAt = 0;
        retryAt = 0;
    }

    void retryAfter(long now, long delay) {
        received();
        retryAt = now + Math.max(0, delay);
    }
}
