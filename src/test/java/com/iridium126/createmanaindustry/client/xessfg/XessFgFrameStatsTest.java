package com.iridium126.createmanaindustry.client.xessfg;

import java.io.BufferedWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class XessFgFrameStatsTest {
    @Test
    void mapsEachQueueInItsOwnClockDomainAndPreservesMissingSamples() {
        var clock = new XessFgClockTimeline();
        long missing = XessFgFrameStats.UNAVAILABLE;
        clock.add(new long[]{10, 500, 100, 100, 1000, 110, 200, 50, 100, 50});
        clock.add(new long[]{10, 700, 122, 100, 1400, 130, 200, 150, 120, 50});
        assertEquals(111, clock.toQpc(600, XessFgClockTimeline.ARC_COPY));
        assertEquals(120, clock.toQpc(1200, XessFgClockTimeline.DIRECT));
        assertEquals(115, clock.toQpc(125, XessFgClockTimeline.RTX_COPY));
        assertEquals(95, clock.toQpc(450, XessFgClockTimeline.ARC_COPY));
        assertEquals(135, clock.toQpc(1500, XessFgClockTimeline.DIRECT));
        assertEquals(missing, clock.toQpc(missing, XessFgClockTimeline.DIRECT));

        var local = new XessFgClockTimeline();
        local.add(new long[]{10, missing, missing, missing, 1000, 110, 200, missing, missing, missing});
        assertEquals(missing, local.toQpc(600, XessFgClockTimeline.ARC_COPY));
    }

    @Test
    void alignsElapsedIntervalWithPrecedingFrameAndLeavesUnavailableMetricsEmpty() throws Exception {
        var profile = new XessFgFrameStats();
        assertNull(XessFgFrameStats.current());
        try (profile) {
            profile.beginFrame(101, 25_000_000);
            XessFgFrameStats.current().route = "opengl";
            profile.endFrame();
            assertNull(XessFgFrameStats.current());

            profile.beginFrame(102, 41_000_000);
            XessFgFrameStats.current().route = "dxgi";
            XessFgFrameStats.current().slotsInUse = 4;
            profile.endFrame();

            var text = new StringWriter();
            try (var writer = new BufferedWriter(text)) {
                profile.write(writer);
            }
            var lines = text.toString().lines().toList();
            var columns = java.util.List.of(lines.getFirst().split(",", -1));
            var first = lines.get(1).split(",", -1);
            var second = lines.get(2).split(",", -1);
            assertEquals(columns.size(), first.length);
            assertEquals(columns.size(), second.length);
            assertEquals("41000000", first[columns.indexOf("next_tick_interval_ns")]);
            assertEquals("", second[columns.indexOf("next_tick_interval_ns")]);
            assertEquals("4", second[columns.indexOf("slots_in_use")]);
            assertEquals("", second[columns.indexOf("sdk_frame_result")]);
            assertEquals("", second[columns.indexOf("arc_texture_upload_gpu_ns")]);
        }
    }
}
