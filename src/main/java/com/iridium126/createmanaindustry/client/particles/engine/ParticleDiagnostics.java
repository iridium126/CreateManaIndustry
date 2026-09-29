package com.iridium126.createmanaindustry.client.particles.engine;

import java.lang.management.ManagementFactory;
import java.util.*;
import org.lwjgl.opengl.*;

/** Opt-in CPU submit/allocations and asynchronous timestamp samples. No GL_TIME_ELAPSED ownership. */
public final class ParticleDiagnostics {
    public static final ParticleDiagnostics INSTANCE = new ParticleDiagnostics();
    private static final int SAMPLES = 240, MARKERS = 32;
    private static final class Frame {
        final int[] queries = new int[MARKERS];
        final String[] names = new String[MARKERS];
        int count;
        boolean pending;
    }
    private final Frame[] frames = {new Frame(),new Frame(),new Frame(),new Frame()};
    private final double[] cpu = new double[SAMPLES], allocations = new double[SAMPLES];
    private final Map<String, Double> gpu = new LinkedHashMap<>();
    private final com.sun.management.ThreadMXBean memory = ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean m ? m : null;
    private int cursor, samples;
    private Frame current;
    private long start, allocatedStart, cpuNanos, allocatedBytes, uploads, calls, draws, readbackLag;
    private long lastUploads, lastCalls, lastDraws, lastReadbackLag;
    private volatile boolean enabled;
    public void enabled(boolean enabled) { this.enabled = enabled; }
    public boolean enabled() { return enabled; }
    public void begin() {
        if (!enabled) return;
        cpuNanos = allocatedBytes = uploads = calls = draws = readbackLag = 0;
        for (Frame f : frames) if (f.pending && GL15.glGetQueryObjecti(f.queries[f.count-1], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
            long previous = GL33.glGetQueryObjectui64(f.queries[0], GL15.GL_QUERY_RESULT);
            for (int i=1; i<f.count; i++) {
                long value = GL33.glGetQueryObjectui64(f.queries[i], GL15.GL_QUERY_RESULT);
                if (!f.names[i-1].equals("world")) gpu.put(f.names[i-1], (value-previous)/1e6);
                previous = value;
            }
            f.pending = false;
        }
        current = null;
        for (Frame f : frames) if (!f.pending) { current = f; break; }
        if (current != null) {
            if (current.queries[0] == 0) GL15.glGenQueries(current.queries);
            current.count = 0;
        }
        resumeCpu(); mark("upload");
    }
    public void resumeCpu() {
        if (!enabled) return;
        start = System.nanoTime(); allocatedStart = allocated();
    }
    private long allocated() {
        return memory != null && memory.isThreadAllocatedMemorySupported() && memory.isThreadAllocatedMemoryEnabled()
                ? memory.getThreadAllocatedBytes(Thread.currentThread().getId()) : 0;
    }
    public void pauseCpu() {
        if (!enabled) return;
        cpuNanos += System.nanoTime()-start; allocatedBytes += Math.max(0,allocated()-allocatedStart);
    }
    public void mark(String name) {
        if (!enabled || current == null || current.count == MARKERS) return;
        int index=current.count++;
        current.names[index]=name;
        GL33.glQueryCounter(current.queries[index], GL33.GL_TIMESTAMP);
    }
    public void upload(long bytes) { if(enabled) uploads+=bytes; }
    public void call() { if(enabled) calls++; }
    /** Counts API submissions; one multi-draw call counts once, irrespective of its sub-draws. */
    public void drawCall() { if(enabled) {calls++;draws++;} }
    public void readback(long lag) { if(enabled) readbackLag=Math.max(readbackLag,lag); }
    public void end() {
        if (!enabled) return;
        mark("end"); pauseCpu();
        if (current != null && current.count > 0) current.pending=true;
        current=null;
        cpu[cursor]=cpuNanos/1e6; allocations[cursor]=allocatedBytes;
        cursor=(cursor+1)%SAMPLES;samples=Math.min(SAMPLES,samples+1);
        lastUploads=uploads;lastCalls=calls;lastDraws=draws;lastReadbackLag=readbackLag;
    }
    public String report() {
        if(samples==0)return "Particle profiling: " + (enabled?"collecting":"off") + "; /cmi particle profile on";
        double[] sorted=Arrays.copyOf(cpu,samples);Arrays.sort(sorted);
        double meanAllocation=Arrays.stream(allocations,0,samples).average().orElse(0);
        return String.format(Locale.ROOT,"CPU submit p50=%.3f ms p95=%.3f ms; allocation=%.0f B/frame; tracked uploads=%d B; tracked GL calls=%d (draw submissions=%d); readback lag=%d generations; GPU latest=%s",
                sorted[samples/2],sorted[Math.min(samples-1,(int)(samples*.95))],meanAllocation,lastUploads,lastCalls,lastDraws,lastReadbackLag,gpu);
    }
    public void close() {
        for(Frame f:frames) {
            for(int q:f.queries)if(q!=0)GL15.glDeleteQueries(q);
            Arrays.fill(f.queries,0);f.pending=false;f.count=0;
        }
        current=null;samples=cursor=0;gpu.clear();
    }
}
