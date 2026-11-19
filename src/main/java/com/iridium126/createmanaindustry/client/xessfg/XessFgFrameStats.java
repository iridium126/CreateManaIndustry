package com.iridium126.createmanaindustry.client.xessfg;

import com.iridium126.createmanaindustry.client.gpu.profile.GpuTimestampRing;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Owned by one whole-client capture. Frame IDs belong to that capture, not to the XeSS or scene counters. */
public final class XessFgFrameStats implements AutoCloseable {
    static final long UNAVAILABLE = Long.MIN_VALUE;
    private static final String NATIVE_COLUMNS = "input_wait_enqueue_ns,fg_toggle_ns,generation_record_submit_ns,"
            + "before_dxgi_ns,dxgi_present_ns,retire_ns,sdk_status_read_ns,cross_bytes,dxgi_hresult,"
            + "sdk_status,sdk_frame_result,sdk_fg_enabled,completed_before,completed_after,gpu_timestamp_mask,profile_setup_ns,"
            + "back_buffer_index,dxgi_sync_interval,dxgi_present_flags,cross_geometry_bytes,cross_color_bytes,"
            + "present_enter_qpc,present_exit_qpc,proxy_swap_chain_address,qpc_frequency,"
            + "color_submit_qpc,color_fence_token,color_gl_fence_at_submit,color_gl_fence_at_enter,color_gl_fence_at_exit,"
            + "color_transfer_fence_at_enter,color_transfer_fence_at_exit,arc_color_fence_observed_qpc";
    private static final int NATIVE_FIELDS = 33, NATIVE_QUERY_MASK = 14;
    private static final int DMA_TICK = 5, INPUTS_TICK = 6;
    private static final int RTX_START_TICK = 7, RTX_END_TICK = 8, DMA_START_TICK = 9;
    private static final int RTX_GEOMETRY_START_TICK = 10, RTX_GEOMETRY_END_TICK = 11;
    private static final int ARC_GEOMETRY_START_TICK = 12, ARC_GEOMETRY_END_TICK = 13;
    private static Frame current;
    private final Map<Long, Frame> frames = new LinkedHashMap<>();
    private final Map<Long, XessFgClockTimeline> clocks = new LinkedHashMap<>();
    private GpuTimestampRing gl;
    private ByteBuffer nativeOutput, gpuOutput, clockOutput;
    private Frame previous;
    private long gpuSession;
    private int pendingGpu;

    enum Stage {
        SESSION_OPEN, XELL_SLEEP, EARLY_SLOT, SLOT_WAIT, DEPTH_MOTION, HUDLESS, FINAL_COLOR, GL_PUBLISH,
        NATIVE_PRESENT, WGL_SWAP, GEOMETRY_PUBLISH, COLOR_SUBMIT
    }

    public static final class Frame {
        private final XessFgFrameStats owner;
        private final long id;
        private final long[] cpu = new long[Stage.values().length];
        private final long[] gpu = new long[3];
        private long[] nativeData;
        private long nextTickIntervalNs = UNAVAILABLE;
        long presentId = UNAVAILABLE, sequence = UNAVAILABLE, sleepStatus = UNAVAILABLE, queued = UNAVAILABLE;
        long sessionId = UNAVAILABLE;
        long earlySlot = UNAVAILABLE, slotsInUse = UNAVAILABLE, renderLuid, generationLuid, disabledChecks;
        private final long[] nativeGpu = new long[XessFgNative.GPU_PROFILE_FIELDS - 1];
        private boolean waitingForGpu;
        boolean interpolation, resetHistory;
        String route = "no_present";

        private Frame(XessFgFrameStats owner, long id) {
            this.owner = owner;
            this.id = id;
            Arrays.fill(cpu, UNAVAILABLE);
            Arrays.fill(gpu, UNAVAILABLE);
            Arrays.fill(nativeGpu, UNAVAILABLE);
        }

        long id() { return id; }

        void bindSession(long nativeSession, long id) {
            sessionId = id;
            if (!owner.clocks.containsKey(id)) owner.sampleClocks(nativeSession, id, true);
        }

        static long start(Frame sample) {
            return sample == null ? 0 : System.nanoTime();
        }

        void complete(Stage stage, long start) {
            cpu[stage.ordinal()] = System.nanoTime() - start;
        }

        boolean startGpu(Stage stage) {
            if (owner.gl == null) owner.gl = new GpuTimestampRing(48, null, owner::recordGl);
            return owner.gl.begin(false, id, stage.ordinal() - Stage.DEPTH_MOTION.ordinal());
        }

        void endGpu(boolean began, boolean success) {
            if (began) owner.gl.end(success, false);
        }

        int present(long session, int slot, int frame, long sequence, boolean interpolate, boolean vsync, ByteBuffer constants) {
            this.sequence = sequence;
            this.interpolation = interpolate;
            if (owner.nativeOutput == null) {
                owner.nativeOutput = directBuffer(NATIVE_FIELDS);
                owner.gpuOutput = directBuffer(XessFgNative.SLOT_COUNT * XessFgNative.GPU_PROFILE_FIELDS);
            }
            long start = System.nanoTime();
            try {
                queued = XessFgNative.presentProfiled(session, slot, frame, sequence, interpolate, vsync, constants,
                        owner.nativeOutput);
                return (int) queued;
            } finally {
                complete(Stage.NATIVE_PRESENT, start);
                nativeData = new long[NATIVE_FIELDS];
                owner.nativeOutput.asLongBuffer().get(nativeData);
                if (nativeData[NATIVE_QUERY_MASK] > 0) {
                    owner.gpuSession = sessionId;
                    waitingForGpu = true;
                    owner.pendingGpu++;
                }
            }
        }
    }

    void recordGl(long frame, long stage, double millis) {
        Frame sample = frames.get(frame);
        if (sample != null) sample.gpu[(int) stage] = Math.round(millis * 1_000_000);
    }

    public static Frame current() { return current; }

    public void beginFrame(long id, long precedingIntervalNs) {
        if (previous != null) previous.nextTickIntervalNs = precedingIntervalNs;
        current = new Frame(this, id);
        frames.put(id, current);
        previous = current;
    }

    public void endFrame() {
        if (current != null && current.owner == this) current = null;
    }

    void sampleClocks(long session, long sessionId, boolean force) {
        var clock = clocks.computeIfAbsent(sessionId, ignored -> new XessFgClockTimeline());
        long now = System.nanoTime();
        if (!force && clock.nextSampleNs - now > 0) return;
        if (clockOutput == null) clockOutput = directBuffer(XessFgNative.CLOCK_PROFILE_FIELDS);
        XessFgNative.sampleClocks(session, clockOutput);
        var anchor = new long[XessFgNative.CLOCK_PROFILE_FIELDS];
        clockOutput.asLongBuffer().get(anchor);
        clock.add(anchor);
        clock.nextSampleNs = now + 1_000_000_000L;
    }

    public Map<Long, java.util.List<long[]>> clockSamples() {
        var samples = new LinkedHashMap<Long, java.util.List<long[]>>();
        clocks.forEach((id, clock) -> samples.put(id, clock.samples()));
        return samples;
    }

    public void poll(long session, long sessionId, boolean finishing) {
        if (gl != null) gl.poll();
        if (sessionId != gpuSession || session == 0) {
            gpuSession = sessionId;
            pendingGpu = 0;
        }
        if (session == 0) return;
        if (clocks.containsKey(sessionId)) sampleClocks(session, sessionId, finishing);
        if (gpuOutput == null) return;
        int samples = XessFgNative.pollGpuProfile(session, gpuOutput);
        var values = gpuOutput.asLongBuffer();
        for (int i = 0; i < samples; i++) {
            long frame = values.get();
            Frame sample = frames.get(frame);
            if (sample == null) {
                values.position(values.position() + XessFgNative.GPU_PROFILE_FIELDS - 1);
                continue;
            }
            values.get(sample.nativeGpu);
            if (sample.waitingForGpu) {
                sample.waitingForGpu = false;
                pendingGpu--;
            }
        }
    }

    public boolean hasPending() {
        return pendingGpu > 0 || (gl != null && gl.pending() > 0);
    }

    public static long beginWgl() {
        return Frame.start(current);
    }

    public static void endWgl(long start) {
        if (start == 0 || current == null) return;
        if ("no_present".equals(current.route)) current.route = "opengl";
        current.complete(Stage.WGL_SWAP, start);
    }

    private static ByteBuffer directBuffer(int longs) {
        return ByteBuffer.allocateDirect(longs * Long.BYTES).order(ByteOrder.nativeOrder());
    }

    public void write(BufferedWriter out) throws IOException {
        out.write("frame,route,next_tick_interval_ns,present_id,sequence,render_luid,generation_luid,sleep_status,early_slot,slots_in_use,"
                + "disabled_checks,interpolate,reset_history,queued,session_open_ns,xell_sleep_ns,early_slot_ns,slot_wait_ns,"
                + "depth_motion_cpu_ns,hudless_cpu_ns,final_color_cpu_ns,gl_publish_ns,native_present_ns,wgl_swap_ns,geometry_publish_ns,color_submit_ns,"
                + "depth_motion_gl_ns,hudless_gl_ns,final_color_gl_ns," + NATIVE_COLUMNS
                + ",producer_geometry_gpu_ns,producer_color_gpu_ns,arc_geometry_dma_gpu_ns,arc_color_dma_gpu_ns,"
                + "arc_texture_upload_gpu_ns,arc_dma_ready_gpu_tick,arc_inputs_ready_gpu_tick,"
                + "rtx_color_copy_start_gpu_tick,rtx_color_copy_end_gpu_tick,arc_color_dma_start_gpu_tick,"
                + "rtx_geometry_copy_start_gpu_tick,rtx_geometry_copy_end_gpu_tick,"
                + "arc_geometry_dma_start_gpu_tick,arc_geometry_dma_end_gpu_tick,session_id,"
                + "arc_dma_ready_qpc,arc_inputs_ready_qpc,rtx_color_copy_start_qpc,rtx_color_copy_end_qpc,"
                + "arc_color_dma_start_qpc,rtx_geometry_copy_start_qpc,rtx_geometry_copy_end_qpc,"
                + "arc_geometry_dma_start_qpc,arc_geometry_dma_end_qpc\n");
        for (Frame frame : frames.values()) {
            out.write(frame.id + "," + frame.route);
            column(out, frame.nextTickIntervalNs);
            column(out, frame.presentId);
            column(out, frame.sequence);
            out.write("," + (frame.renderLuid == 0 ? "" : Long.toUnsignedString(frame.renderLuid, 16))
                    + "," + (frame.generationLuid == 0 ? "" : Long.toUnsignedString(frame.generationLuid, 16)));
            column(out, frame.sleepStatus);
            column(out, frame.earlySlot);
            column(out, frame.slotsInUse);
            column(out, frame.disabledChecks);
            out.write("," + frame.interpolation + "," + frame.resetHistory);
            column(out, frame.queued);
            for (long time : frame.cpu) column(out, time);
            for (long time : frame.gpu) column(out, time);
            for (int i = 0; i < NATIVE_FIELDS; i++) column(out, frame.nativeData == null ? UNAVAILABLE : frame.nativeData[i]);
            for (long time : frame.nativeGpu) column(out, time);
            column(out, frame.sessionId);
            var clock = clocks.get(frame.sessionId);
            column(out, calibratedTick(clock, frame, DMA_TICK, XessFgClockTimeline.ARC_COPY));
            column(out, calibratedTick(clock, frame, INPUTS_TICK, XessFgClockTimeline.DIRECT));
            column(out, calibratedTick(clock, frame, RTX_START_TICK, XessFgClockTimeline.RTX_COPY));
            column(out, calibratedTick(clock, frame, RTX_END_TICK, XessFgClockTimeline.RTX_COPY));
            column(out, calibratedTick(clock, frame, DMA_START_TICK, XessFgClockTimeline.ARC_COPY));
            column(out, calibratedTick(clock, frame, RTX_GEOMETRY_START_TICK, XessFgClockTimeline.RTX_COPY));
            column(out, calibratedTick(clock, frame, RTX_GEOMETRY_END_TICK, XessFgClockTimeline.RTX_COPY));
            column(out, calibratedTick(clock, frame, ARC_GEOMETRY_START_TICK, XessFgClockTimeline.ARC_COPY));
            column(out, calibratedTick(clock, frame, ARC_GEOMETRY_END_TICK, XessFgClockTimeline.ARC_COPY));
            out.newLine();
        }
    }

    private static long calibratedTick(XessFgClockTimeline clock, Frame frame, int field, int queue) {
        return clock == null ? UNAVAILABLE : clock.toQpc(frame.nativeGpu[field], queue);
    }

    private static void column(BufferedWriter out, long value) throws IOException {
        out.write(',');
        if (value != UNAVAILABLE) out.write(Long.toString(value));
    }

    @Override
    public void close() {
        endFrame();
        if (gl != null) gl.close();
    }
}
