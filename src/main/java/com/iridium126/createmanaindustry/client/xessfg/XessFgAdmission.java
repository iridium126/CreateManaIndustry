package com.iridium126.createmanaindustry.client.xessfg;

/** Render-side backpressure hysteresis for XeSS-FG presentation. */
final class XessFgAdmission {
    static final int NONE = 0, TRANSFER_PRESSURE = 1, STALE_PRESENT = 2, PRESENT_PRESSURE = 3;

    // Must match AdmissionState in XessFg.cpp.
    private static final long TRANSFER_FULL = 1, WAITING_STALE = 2, PRESENT_BUSY = 4;
    private static final int STATE_BITS = 3;
    private static final int PRESSURE_SAMPLES = 2, RECOVERY_FRAMES = 8;

    private int consecutiveFull, consecutivePresentPressure, recoveredFrames, reason;
    private long lastProgress = Long.MIN_VALUE;

    int update(long state) {
        return update((state & TRANSFER_FULL) != 0, (state & WAITING_STALE) != 0,
                (state & PRESENT_BUSY) != 0, state >>> STATE_BITS);
    }

    int update(boolean full, boolean stale, boolean rejected, long progress) {
        if (reason != NONE) {
            boolean progressed = progress != lastProgress;
            lastProgress = progress;
            if (full || rejected || (stale && !progressed)) recoveredFrames = 0;
            else if (progressed && ++recoveredFrames >= RECOVERY_FRAMES) reset();
            return reason;
        }
        consecutiveFull = full ? Math.min(consecutiveFull + 1, PRESSURE_SAMPLES) : 0;
        consecutivePresentPressure = stale || rejected
                ? Math.min(consecutivePresentPressure + 1, PRESSURE_SAMPLES) : 0;
        if (stale) reason = STALE_PRESENT;
        else if (consecutivePresentPressure == PRESSURE_SAMPLES) reason = PRESENT_PRESSURE;
        else if (consecutiveFull == PRESSURE_SAMPLES) reason = TRANSFER_PRESSURE;
        if (reason != NONE) {
            recoveredFrames = 0;
            lastProgress = progress;
        }
        return reason;
    }

    int reset() {
        consecutiveFull = consecutivePresentPressure = recoveredFrames = reason = 0;
        lastProgress = Long.MIN_VALUE;
        return NONE;
    }

    boolean pending() {
        return reason != NONE;
    }
}
