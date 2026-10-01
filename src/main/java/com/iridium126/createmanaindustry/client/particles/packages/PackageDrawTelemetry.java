package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.Arrays;
import java.util.Locale;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/** Independent timestamp ring: never owns TIME_ELAPSED, waits, or overwrites pending samples. */
public final class PackageDrawTelemetry implements AutoCloseable {
    private static final int BANKS=4,SAMPLES=240;
    private final int[] starts=new int[BANKS],ends=new int[BANKS];
    private final boolean[] pending=new boolean[BANKS];
    private final double[] cpu=new double[SAMPLES],gpu=new double[SAMPLES];
    private int cursor,slot=-1,cpuCursor,gpuCursor,cpuSamples,gpuSamples;
    private long cpuStart,skipped;
    private boolean active;
    public void begin(boolean enabled) {
        if(active)throw new IllegalStateException("Nested package draw measurement");
        if(!enabled)return;
        active=true;cpuStart=System.nanoTime();slot=-1;
        for(int i=0;i<BANKS;i++) {
            int candidate=(cursor+i)%BANKS;
            if(!pending[candidate]){slot=candidate;break;}
        }
        if(slot<0){skipped++;return;}
        if(starts[slot]==0){starts[slot]=GL15.glGenQueries();ends[slot]=GL15.glGenQueries();}
        GL33.glQueryCounter(starts[slot],GL33.GL_TIMESTAMP);
    }
    public void end() {
        if(!active)return;
        active=false;cpu[cpuCursor]=(System.nanoTime()-cpuStart)/1e6;
        cpuCursor=(cpuCursor+1)%SAMPLES;cpuSamples=Math.min(cpuSamples+1,SAMPLES);
        if(slot<0)return;
        GL33.glQueryCounter(ends[slot],GL33.GL_TIMESTAMP);pending[slot]=true;
        cursor=(slot+1)%BANKS;slot=-1;
    }
    /** Sum of newly completed GPU samples for the engine's lagged throttle costing. */
    public double poll() {
        double completed=0;
        for(int i=0;i<BANKS;i++)if(pending[i] && GL15.glGetQueryObjecti(ends[i],GL15.GL_QUERY_RESULT_AVAILABLE)!=0) {
            long finish=GL33.glGetQueryObjectui64(ends[i],GL15.GL_QUERY_RESULT);
            long start=GL33.glGetQueryObjectui64(starts[i],GL15.GL_QUERY_RESULT);
            double ms=Math.max(0,finish-start)/1e6;completed+=ms;
            gpu[gpuCursor]=ms;gpuCursor=(gpuCursor+1)%SAMPLES;gpuSamples=Math.min(gpuSamples+1,SAMPLES);pending[i]=false;
        }
        return completed;
    }
    public int pending(){int count=0;for(boolean value:pending)if(value)count++;return count;}
    public long skipped(){return skipped;}
    public int cpuSamples(){return cpuSamples;}
    public int gpuSamples(){return gpuSamples;}
    public String report() {
        if(cpuSamples==0)return "unmeasured";
        return String.format(Locale.ROOT,"CPU p50/p95=%.3f/%.3fms, GPU p50/p95=%.3f/%.3fms (%d samples), pending=%d, skipped=%d",
                percentile(cpu,cpuSamples,.5),percentile(cpu,cpuSamples,.95),percentile(gpu,gpuSamples,.5),percentile(gpu,gpuSamples,.95),gpuSamples,pending(),skipped);
    }
    private static double percentile(double[] values,int count,double fraction) {
        if(count==0)return 0;
        double[] sorted=Arrays.copyOf(values,count);Arrays.sort(sorted);return sorted[Math.min(count-1,(int)(count*fraction))];
    }
    @Override public void close() {
        for(int i=0;i<BANKS;i++) {
            if(starts[i]!=0)GL15.glDeleteQueries(starts[i]);if(ends[i]!=0)GL15.glDeleteQueries(ends[i]);
            starts[i]=ends[i]=0;pending[i]=false;
        }
        active=false;slot=-1;cursor=cpuCursor=gpuCursor=cpuSamples=gpuSamples=0;skipped=0;
    }
}
