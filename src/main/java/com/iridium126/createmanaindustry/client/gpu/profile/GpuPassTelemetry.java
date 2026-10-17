package com.iridium126.createmanaindustry.client.gpu.profile;

import java.util.Arrays;
import java.util.Locale;

/** Owner-scoped CPU submit and asynchronous GPU percentiles. Timestamp pairs can
 * be nested across owners without taking the process-wide TIME_ELAPSED target. */
public final class GpuPassTelemetry implements AutoCloseable {
    private static final int SAMPLES=240;
    private final double[] cpu=new double[SAMPLES],gpu=new double[SAMPLES];
    private int cpuCursor,gpuCursor,cpuSamples,gpuSamples;
    private long cpuStart,skipped;
    private boolean active,timed;
    private double completed;
    private final GpuTimestampRing timer=new GpuTimestampRing(4,ms->{
        gpu[gpuCursor]=ms;gpuCursor=(gpuCursor+1)%SAMPLES;gpuSamples=Math.min(SAMPLES,gpuSamples+1);completed+=ms;
    });

    public void begin(boolean enabled) {
        if(active)throw new IllegalStateException("Nested GPU pass measurement");
        if(!enabled)return;
        timed=timer.begin(false);if(!timed)skipped++;
        active=true;cpuStart=System.nanoTime();
    }
    public void end(){end(true);}
    public void end(boolean publish) {
        if(!active)return;
        active=false;
        if(publish) {
            cpu[cpuCursor]=(System.nanoTime()-cpuStart)/1e6;
            cpuCursor=(cpuCursor+1)%SAMPLES;cpuSamples=Math.min(SAMPLES,cpuSamples+1);
        }
        if(timed)timer.end(publish,false);
        timed=false;
    }
    /** Completed cost since the last poll; each sample contributes exactly once. */
    public double poll(){timer.poll();double result=completed;completed=0;return result;}
    public long skipped(){return skipped;}
    public int pending(){return timer.pending();}
    public int cpuSamples(){return cpuSamples;}
    public int gpuSamples(){return gpuSamples;}
    public String report() {
        if(cpuSamples==0)return "unmeasured";
        return String.format(Locale.ROOT,"CPU p50/p95/p99=%.3f/%.3f/%.3fms, GPU p50/p95/p99=%.3f/%.3f/%.3fms (%d samples), skipped=%d",
                percentile(cpu,cpuSamples,.5),percentile(cpu,cpuSamples,.95),percentile(cpu,cpuSamples,.99),
                percentile(gpu,gpuSamples,.5),percentile(gpu,gpuSamples,.95),percentile(gpu,gpuSamples,.99),gpuSamples,skipped);
    }
    private static double percentile(double[] values,int count,double fraction) {
        if(count==0)return 0;
        double[] sorted=Arrays.copyOf(values,count);Arrays.sort(sorted);
        return sorted[Math.max(0,(int)Math.ceil(count*fraction)-1)];
    }
    @Override public void close() {
        timer.close();active=timed=false;cpuCursor=gpuCursor=cpuSamples=gpuSamples=0;skipped=0;completed=0;
    }
}
