package com.iridium126.createmanaindustry.client.particles.engine;

import java.lang.management.ManagementFactory;
import java.util.*;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.gpu.profile.GpuBudgetLedger;
import com.iridium126.createmanaindustry.client.gpu.profile.ProfileReportFormat;

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
    private volatile boolean requested,enabled;
    public void enabled(boolean enabled) { requested=enabled; }
    public boolean enabled() { return enabled; }
    public boolean hasSamples() { return samples>0; }
    /** Render-thread boundary: command changes cannot split an active measurement. */
    public void applyRequestedState() {
        boolean next=requested;
        if(next==enabled)return;
        releaseQueries();
        if(next){samples=cursor=0;gpu.clear();}
        enabled=next;
        com.iridium126.createmanaindustry.client.gpu.profile.GpuQueueAccounting.collecting(next);
        com.iridium126.createmanaindustry.client.packages.feedback.PackageCpuDiagnostics.enabled(next);
        com.iridium126.createmanaindustry.client.packages.world.PackageWorldRuntime.profiling(next);
        if(com.iridium126.createmanaindustry.CreateManaIndustry.HEX_ACTIVE)
            com.iridium126.createmanaindustry.client.hex.pattern.HexMetrics.collecting(next);
    }
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
    @SuppressWarnings("deprecation") // Preserve getId() overrides on custom Thread subclasses.
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
        com.iridium126.createmanaindustry.client.packages.feedback.PackageCpuDiagnostics.frame();
    }
    public String summary(GpuBudgetLedger.Snapshot frame) {
        return formatReport(frame,false);
    }
    public String report(GpuBudgetLedger.Snapshot frame) {
        return formatReport(frame,true);
    }
    private String formatReport(GpuBudgetLedger.Snapshot frame,boolean details) {
        String header=enabled?"[CMI profile] collecting; waiting for first frame":"[CMI profile] off; /cmi particle profile on";
        if(samples==0)return header;
        header="[CMI profile] "+(enabled?"collecting":"stopped; last capture")+"; frames="+samples+"/"+SAMPLES
                +(details?"; details":"; /cmi particle profile details");
        double[] sorted=Arrays.copyOf(cpu,samples);Arrays.sort(sorted);
        double meanAllocation=Arrays.stream(allocations,0,samples).average().orElse(0);
        var report=new StringBuilder(header).append(String.format(Locale.ROOT,
                "\nCPU submit p50/p95=%.3f/%.3f ms; prepare latest=%.3f ms; alloc=%s/frame",
                sorted[samples/2],sorted[Math.min(samples-1,(int)(samples*.95))],frame.cpuNanos()/1e6,ProfileReportFormat.bytes(meanAllocation)));
        report.append(String.format(Locale.ROOT,"\nGPU completed latest=%.3f ms; stages latest (ms%s): ",frame.gpuMillis(),details?"":", top 5"))
                .append(details?ProfileReportFormat.timings(gpu):ProfileReportFormat.slowest(gpu,5));
        report.append("\nIO latest: upload=").append(ProfileReportFormat.bytes(frame.uploadedBytes()))
                .append("; readback=").append(ProfileReportFormat.bytes(frame.readbackBytes()))
                .append("; tracked GL/draw=").append(lastCalls).append('/').append(lastDraws)
                .append("; readback lag=").append(lastReadbackLag).append(" gen");
        if(details&&lastUploads!=frame.uploadedBytes())report.append("; particle tracked upload=").append(ProfileReportFormat.bytes(lastUploads));
        if((frame.accepted()|frame.deferred()|frame.rejected()|frame.backlog())!=0)
            report.append("\nQueue latest accepted/deferred/rejected/backlog=").append(frame.accepted()).append('/')
                    .append(frame.deferred()).append('/').append(frame.rejected()).append('/').append(frame.backlog());
        return report.toString();
    }
    public void close() {
        releaseQueries();samples=cursor=0;gpu.clear();
    }
    private void releaseQueries() {
        for(Frame f:frames) {
            for(int q:f.queries)if(q!=0)GL15.glDeleteQueries(q);
            Arrays.fill(f.queries,0);f.pending=false;f.count=0;
        }
        current=null;
    }
}
