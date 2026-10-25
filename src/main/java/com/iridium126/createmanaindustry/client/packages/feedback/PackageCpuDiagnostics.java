package com.iridium126.createmanaindustry.client.packages.feedback;


import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

/** Opt-in render/client-thread metrics. Phase totals are inclusive and flushed per rendered frame. */
public final class PackageCpuDiagnostics {
    public enum Phase { CAPTURE, UPLOAD, PREPARE, POLL, FEEDBACK, VIEW, SOLVER, READBACK, PUBLISH }
    private static final int SAMPLES=240;
    private static final java.lang.management.ThreadMXBean thread=ManagementFactory.getThreadMXBean();
    private static final com.sun.management.ThreadMXBean memory=thread instanceof com.sun.management.ThreadMXBean m?m:null;
    private static final long[][] wall=new long[Phase.values().length][SAMPLES],cpu=new long[Phase.values().length][SAMPLES],alloc=new long[Phase.values().length][SAMPLES];
    private static final long[] pendingWall=new long[Phase.values().length],pendingCpu=new long[Phase.values().length],pendingAlloc=new long[Phase.values().length],startedCpu=new long[Phase.values().length],startedAlloc=new long[Phase.values().length];
    private static volatile boolean enabled;
    private static int cursor,samples,steps,live,indices,moving,dispatches,lastSteps,lastLive,lastIndices,lastMoving,lastDispatches;
    private static long downloads,lastDownloads;
    private static String waiting="none",lastWaiting="none";
    private PackageCpuDiagnostics(){}
    public static void enabled(boolean next){if(next&&!enabled)clear();enabled=next;}
    public static long begin(Phase phase){
        if(!enabled)return 0;
        int i=phase.ordinal();startedCpu[i]=threadCpu();startedAlloc[i]=allocated();return System.nanoTime();
    }
    public static void end(Phase phase,long start){
        if(start==0||!enabled)return;
        long elapsed=System.nanoTime()-start;int i=phase.ordinal();pendingWall[i]+=Math.max(0,elapsed);
        if(startedCpu[i]>=0)pendingCpu[i]+=Math.max(0,threadCpu()-startedCpu[i]);
        if(startedAlloc[i]>=0)pendingAlloc[i]+=Math.max(0,allocated()-startedAlloc[i]);
    }
    private static long threadCpu(){return thread.isCurrentThreadCpuTimeSupported()&&thread.isThreadCpuTimeEnabled()?thread.getCurrentThreadCpuTime():-1;}
    private static long allocated(){return memory!=null&&memory.isThreadAllocatedMemorySupported()&&memory.isThreadAllocatedMemoryEnabled()?memory.getThreadAllocatedBytes(Thread.currentThread().threadId()):-1;}
    public static void dispatch(){if(enabled)dispatches++;}
    public static void downloaded(int bytes){if(enabled)downloads+=bytes;}
    public static void step(int structures){if(enabled){steps++;moving=Math.max(moving,structures);}}
    public static void context(int active,int upper,String reason){if(enabled){live=active;indices=upper;waiting=reason;}}
    public static void frame(){
        if(!enabled)return;
        for(int i=0;i<wall.length;i++){wall[i][cursor]=pendingWall[i];cpu[i][cursor]=pendingCpu[i];alloc[i][cursor]=pendingAlloc[i];}
        Arrays.fill(pendingWall,0);Arrays.fill(pendingCpu,0);Arrays.fill(pendingAlloc,0);
        cursor=(cursor+1)%SAMPLES;samples=Math.min(samples+1,SAMPLES);
        lastSteps=steps;lastLive=live;lastIndices=indices;lastMoving=moving;lastDispatches=dispatches;lastDownloads=downloads;lastWaiting=waiting;
        steps=moving=dispatches=0;downloads=0;
    }
    private static long percentile(long[] input,double q){var sorted=Arrays.copyOf(input,samples);Arrays.sort(sorted);return sorted[Math.max(0,(int)Math.ceil(q*samples)-1)];}
    public static String summary(){
        if(samples==0)return "";
        var phases=new java.util.LinkedHashMap<String,Double>();
        for(var phase:Phase.values()){
            long nanos=percentile(wall[phase.ordinal()],.95);
            if(nanos>0)phases.put(phase.name(),nanos/1e6);
        }
        if(phases.isEmpty())return "";
        return "Package CPU p95 (inclusive ms, top 3): "+com.iridium126.createmanaindustry.client.gpu.profile.ProfileReportFormat.slowest(phases,3);
    }
    public static String report(){
        if(samples==0)return "";
        var out=new StringBuilder();
        for(var phase:Phase.values()){
            int i=phase.ordinal();if(Arrays.stream(wall[i],0,samples).noneMatch(n->n>0))continue;
            out.append(String.format(Locale.ROOT,"%sPackage CPU %s: wall p50/p95=%.3f/%.3f ms; CPU p95=%s; alloc p95=%s",
                    out.isEmpty()?"":"\n",phase,percentile(wall[i],.5)/1e6,percentile(wall[i],.95)/1e6,
                    threadCpu()<0?"n/a":String.format(Locale.ROOT,"%.3f ms",percentile(cpu[i],.95)/1e6),
                    allocated()<0?"n/a":com.iridium126.createmanaindustry.client.gpu.profile.ProfileReportFormat.bytes(percentile(alloc[i],.95))));
        }
        if((lastSteps|lastLive|lastIndices|lastMoving|lastDispatches|lastDownloads)!=0||!lastWaiting.equals("none"))
            out.append(out.isEmpty()?"":"\n").append("Package work: steps=").append(lastSteps).append("; live/indices=").append(lastLive).append('/').append(lastIndices)
                    .append("; moving=").append(lastMoving).append("; dispatches=").append(lastDispatches)
                    .append("; readback=").append(com.iridium126.createmanaindustry.client.gpu.profile.ProfileReportFormat.bytes(lastDownloads))
                    .append(lastWaiting.equals("none")?"":"; waiting="+lastWaiting);
        return out.toString();
    }
    public static void reset(){clear();}
    private static void clear(){cursor=samples=steps=live=indices=moving=dispatches=lastSteps=lastLive=lastIndices=lastMoving=lastDispatches=0;downloads=lastDownloads=0;waiting=lastWaiting="none";Arrays.fill(pendingWall,0);Arrays.fill(pendingCpu,0);Arrays.fill(pendingAlloc,0);}
}
