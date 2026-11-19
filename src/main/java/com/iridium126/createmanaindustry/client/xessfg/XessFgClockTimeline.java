package com.iridium126.createmanaindustry.client.xessfg;

import java.util.ArrayList;
import java.util.List;

/** Raw queue/QPC calibration samples belonging to one native session in a capture. */
final class XessFgClockTimeline {
    static final int ARC_COPY = 1, ARC_PREPARE = 4, RTX_COPY = 7;
    private final List<long[]> anchors = new ArrayList<>();
    long nextSampleNs;

    void add(long[] anchor) {
        anchors.add(anchor);
    }

    List<long[]> samples() {
        return List.copyOf(anchors);
    }

    long toQpc(long tick, int queue) {
        if (tick == XessFgFrameStats.UNAVAILABLE) return tick;
        long[] before = null, after = null;
        for (long[] anchor : anchors) {
            if (anchor[0] <= 0 || anchor[queue + 2] <= 0) continue;
            long gpu = anchor[queue];
            if (gpu <= tick && (before == null || gpu > before[queue])) before = anchor;
            if (gpu >= tick && (after == null || gpu < after[queue])) after = anchor;
        }
        if (before != null && after != null && before[queue] < after[queue] && before[queue + 1] < after[queue + 1]) {
            long gpuDelta = after[queue] - before[queue];
            long cpuDelta = after[queue + 1] - before[queue + 1];
            double ticksPerGpuTick = (double) cpuDelta / gpuDelta;
            return before[queue + 1] + Math.round((tick - before[queue]) * ticksPerGpuTick);
        }
        long[] anchor = before != null ? before : after;
        if (anchor == null) return XessFgFrameStats.UNAVAILABLE;
        return anchor[queue + 1] + Math.round((tick - anchor[queue]) * ((double) anchor[0] / anchor[queue + 2]));
    }
}
