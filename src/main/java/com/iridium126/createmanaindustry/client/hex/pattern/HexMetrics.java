package com.iridium126.createmanaindustry.client.hex.pattern;
import java.lang.management.ManagementFactory;
import java.util.*;
import com.iridium126.createmanaindustry.client.particles.engine.ParticleDiagnostics;
import com.iridium126.createmanaindustry.client.gpu.profile.GpuPassTelemetry;
/** Opt-in CPU/allocation and asynchronous GPU measurements, separate from ordinary particles. */
public final class HexMetrics {
    public interface Scope extends AutoCloseable {void close();}
    private static final Scope NOOP=()->{};
    private static final java.lang.management.ThreadMXBean THREAD=ManagementFactory.getThreadMXBean();
    private static final com.sun.management.ThreadMXBean ALLOC=THREAD instanceof com.sun.management.ThreadMXBean bean?bean:null;
    private static final Map<String,Sample> samples=new LinkedHashMap<>();
    private static final class Sample {
        final GpuPassTelemetry timer=new GpuPassTelemetry();final double[] thread=new double[240],bytes=new double[240];
        final com.iridium126.createmanaindustry.client.gpu.profile.GpuBudgetLedger budget=new com.iridium126.createmanaindustry.client.gpu.profile.GpuBudgetLedger();
        long sequence;
        int cursor,count;boolean open;
    }
    private HexMetrics(){}
    private static long cpu(){return THREAD.isCurrentThreadCpuTimeSupported()?THREAD.getCurrentThreadCpuTime():-1;}
    private static long alloc(){return ALLOC!=null&&ALLOC.isThreadAllocatedMemorySupported()?ALLOC.getThreadAllocatedBytes(Thread.currentThread().threadId()):-1;}
    public static Scope phase(HexPass pass,String phase){if(!ParticleDiagnostics.INSTANCE.enabled())return NOOP;
        var sample=samples.computeIfAbsent(pass+" "+phase,k->new Sample());if(sample.open)return NOOP;sample.open=true;
        sample.budget.begin(++sample.sequence);sample.budget.gpu(sample.timer.poll());
        var accounting=sample.budget.prepare();
        try{sample.timer.begin(true);}catch(RuntimeException|LinkageError failure){sample.open=false;accounting.close();throw failure;}
        long cpu=cpu(),bytes=alloc();
        return ()->{try {sample.timer.end();sample.thread[sample.cursor]=cpu<0?-1:(cpu()-cpu)/1e6;sample.bytes[sample.cursor]=bytes<0?-1:alloc()-bytes;sample.cursor=(sample.cursor+1)%240;sample.count=Math.min(240,sample.count+1);}finally{sample.open=false;accounting.close();}};
    }
    private static double p95(double[] a,int n){if(n==0)return 0;var b=Arrays.copyOf(a,n);Arrays.sort(b);return b[Math.min(n-1,(int)(n*.95))];}
    public static String report(){
        var b=new java.util.StringJoiner("\n");
        for(var e:samples.entrySet()){
            var s=e.getValue();if(s.count==0)continue;
            b.add("Hex "+e.getKey()+": "+s.timer.report()+String.format(Locale.ROOT,"; thread CPU p95=%s; alloc p95=%s",
                    p95(s.thread,s.count)<0?"n/a":String.format(Locale.ROOT,"%.3f ms",p95(s.thread,s.count)),
                    p95(s.bytes,s.count)<0?"n/a":com.iridium126.createmanaindustry.client.gpu.profile.ProfileReportFormat.bytes(p95(s.bytes,s.count))));
        }
        return b.toString();
    }
    public static String summary(){
        return samples.entrySet().stream().filter(e->e.getValue().count>0)
                .sorted(java.util.Comparator.comparingDouble((Map.Entry<String,Sample> e)->e.getValue().timer.cpuP95Millis()).reversed())
                .limit(3).map(e->"Hex "+e.getKey()+": "+e.getValue().timer.summary())
                .collect(java.util.stream.Collectors.joining("\n"));
    }
    public static void collecting(boolean enabled){if(enabled)close();else for(var s:samples.values())s.timer.configure(false,false);}
    public static void close(){for(var s:samples.values())s.timer.close();samples.clear();}
}
