package com.iridium126.createmanaindustry.client.gpu.profile;

import java.util.Arrays;
import java.util.Locale;

/** Owner-scoped CPU submit and asynchronous GPU percentiles. Timestamp pairs can
 * be nested across owners without taking the process-wide TIME_ELAPSED target. */
public final class GpuPassTelemetry implements AutoCloseable {
    private static final int SAMPLES=240;
    private final double[] cpu=new double[SAMPLES],gpu=new double[SAMPLES];
    private final long[] cpuFrames=new long[SAMPLES];
    private long activeFrame=-1;
    private final long[] frames=new long[SAMPLES],generations=new long[SAMPLES];
    private int cpuCursor,gpuCursor,cpuSamples,gpuSamples;
    private long cpuStart,skipped,discarded,unpublished;
    private boolean active,timed,timingEnabled,detailed;
    private double completed;
    private final GpuTimestampRing timer=new GpuTimestampRing(4,null,(frame,generation,ms)->{
        if(detailed){frames[gpuCursor]=frame;generations[gpuCursor]=generation;gpu[gpuCursor]=ms;gpuCursor=(gpuCursor+1)%SAMPLES;gpuSamples=Math.min(SAMPLES,gpuSamples+1);}
        completed+=ms;
    });

    /** Apply at a render boundary, including for owners that will not draw this frame.
     * Old in-flight samples are discarded on mode changes, so a lightweight sample
     * can never enter a later detailed session. Stopping preserves the last report. */
    public void configure(boolean gpuTiming,boolean details) {
        if(active)throw new IllegalStateException("GPU measurement mode changed inside a pass");
        boolean nextTiming=gpuTiming||details;
        if(timingEnabled==nextTiming && detailed==details)return;
        discardPending();
        if(details&&!detailed)resetSamples();
        timingEnabled=nextTiming;detailed=details;
    }
    public void begin(boolean enabled) {
        begin(enabled,enabled);
    }
    public void begin(boolean gpuTiming,boolean details) {begin(gpuTiming,details,-1,-1);}
    public void begin(boolean gpuTiming,boolean details,long frame,long generation) {
        if(active)throw new IllegalStateException("Nested GPU pass measurement");
        configure(gpuTiming,details);
        if(!timingEnabled)return;
        timed=timer.begin(false,frame,generation);if(!timed&&detailed)skipped++;
        active=true;activeFrame=frame;if(detailed)cpuStart=System.nanoTime();
    }
    public void end(){end(true);}
    public void end(boolean publish) {
        if(!active)return;
        active=false;
        if(!publish&&detailed)unpublished++;
        if(publish&&detailed) {
            cpuFrames[cpuCursor]=activeFrame;cpu[cpuCursor]=(System.nanoTime()-cpuStart)/1e6;
            cpuCursor=(cpuCursor+1)%SAMPLES;cpuSamples=Math.min(SAMPLES,cpuSamples+1);
        }
        if(timed)timer.end(publish,false);
        timed=false;
    }
    /** Completed cost since the last poll; each sample contributes exactly once. */
    public double poll(){if(timingEnabled)timer.poll();double result=completed;completed=0;return result;}
    public void discardPending(){if(active)throw new IllegalStateException("Open GPU measurement");discarded+=timer.validPending();timer.close();completed=0;}
    public long skipped(){return skipped;}
    public int pending(){return timer.pending();}
    public int cpuSamples(){return cpuSamples;}
    public int gpuSamples(){return gpuSamples;}
    public double cpuP95Millis(){return percentile(cpu,cpuSamples,.95);}
    public String summary() {
        if(cpuSamples==0)return "";
        String gpuTime=gpuSamples==0?"pending":String.format(Locale.ROOT,"%.3f/%.3f ms",percentile(gpu,gpuSamples,.5),percentile(gpu,gpuSamples,.95));
        return String.format(Locale.ROOT,"CPU p50/p95=%.3f/%.3f ms; GPU p50/p95=%s",
                percentile(cpu,cpuSamples,.5),cpuP95Millis(),gpuTime);
    }
    public String report() {
        if(cpuSamples==0)return "unmeasured";
        int oldest=(gpuCursor-gpuSamples+SAMPLES)%SAMPLES,newest=(gpuCursor+SAMPLES-1)%SAMPLES;
        String context=gpuSamples>0&&frames[newest]>=0?"; GPU frames="+frames[oldest]+".."+frames[newest]
                +"; generations="+generations[oldest]+".."+generations[newest]:"";
        return summary()+"; samples CPU/GPU="+cpuSamples+"/"+gpuSamples+"; skipped="+skipped+"; pending="+pending()+"; discarded="+discarded+"; unpublished="+unpublished+context;
    }
    /** Read-only view of samples submitted in the owner's current capture window. */
    public String report(long firstFrame,long lastFrame,boolean details) {
        double[] cs=new double[cpuSamples],gs=new double[gpuSamples];int nc=0,ng=0;long gf=-1,gl=-1,g0=-1,g1=-1,lastSample=-1,lastGeneration=-1;
        for(int n=0;n<cpuSamples;n++){
            int i=(cpuCursor-cpuSamples+n+SAMPLES)%SAMPLES;
            if(cpuFrames[i]>=firstFrame&&cpuFrames[i]<=lastFrame)cs[nc++]=cpu[i];
        }
        for(int n=0;n<gpuSamples;n++){
            int i=(gpuCursor-gpuSamples+n+SAMPLES)%SAMPLES;
            if(frames[i]>=0){lastSample=frames[i];lastGeneration=generations[i];}
            if(frames[i]>=firstFrame&&frames[i]<=lastFrame){gs[ng++]=gpu[i];if(gf<0){gf=frames[i];g0=generations[i];}gl=frames[i];g1=generations[i];}
        }
        if(nc==0&&ng==0){
            String state=lastSample>=0?"inactive; current window unmeasured; last GPU frame="+lastSample+"; generation="+lastGeneration:"unmeasured";
            return state+(details?"; skipped="+skipped+"; pending="+pending()+"; discarded="+discarded+"; unpublished="+unpublished:"");
        }
        String cpuText=nc==0?"unmeasured":String.format(Locale.ROOT,"%.3f/%.3f ms",percentile(cs,nc,.5),percentile(cs,nc,.95));
        String gpuText=ng==0?(pending()>0?"pending":"unmeasured"):String.format(Locale.ROOT,"%.3f/%.3f ms",percentile(gs,ng,.5),percentile(gs,ng,.95));
        String result="CPU p50/p95="+cpuText+"; GPU p50/p95="+gpuText;
        if(details)result+="; samples CPU/GPU="+nc+"/"+ng+"; skipped="+skipped+"; pending="+pending()+"; discarded="+discarded+"; unpublished="+unpublished
                +(ng==0?"":"; GPU frames="+gf+".."+gl+"; generations="+g0+".."+g1);
        return result;
    }
    private static double percentile(double[] values,int count,double fraction) {
        if(count==0)return 0;
        double[] sorted=Arrays.copyOf(values,count);Arrays.sort(sorted);
        return sorted[Math.max(0,(int)Math.ceil(count*fraction)-1)];
    }
    private void resetSamples(){cpuCursor=gpuCursor=cpuSamples=gpuSamples=0;skipped=discarded=unpublished=0;}
    @Override public void close() {
        timer.close();active=timed=timingEnabled=detailed=false;resetSamples();completed=0;
    }
}
