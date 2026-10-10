package com.iridium126.createmanaindustry.client.particles.hex;
import java.lang.management.ManagementFactory;
import java.util.*;
import com.iridium126.createmanaindustry.client.particles.engine.ParticleDiagnostics;
import com.iridium126.createmanaindustry.client.particles.packages.PackageDrawTelemetry;
/** Opt-in CPU/allocation and asynchronous GPU measurements, separate from ordinary particles. */
public final class HexMetrics {
    public interface Scope extends AutoCloseable {void close();}
    private static final Scope NOOP=()->{};
    private static final java.lang.management.ThreadMXBean THREAD=ManagementFactory.getThreadMXBean();
    private static final com.sun.management.ThreadMXBean ALLOC=THREAD instanceof com.sun.management.ThreadMXBean bean?bean:null;
    private static final Map<String,Sample> samples=new LinkedHashMap<>();
    private static final class Sample {
        final PackageDrawTelemetry timer=new PackageDrawTelemetry();final double[] thread=new double[240],bytes=new double[240];
        int cursor,count;boolean open;
    }
    private HexMetrics(){}
    private static long cpu(){return THREAD.isCurrentThreadCpuTimeSupported()?THREAD.getCurrentThreadCpuTime():-1;}
    private static long alloc(){return ALLOC!=null&&ALLOC.isThreadAllocatedMemorySupported()?ALLOC.getThreadAllocatedBytes(Thread.currentThread().threadId()):-1;}
    public static Scope phase(HexPass pass,String phase){if(!ParticleDiagnostics.INSTANCE.enabled())return NOOP;
        var sample=samples.computeIfAbsent(pass+" "+phase,k->new Sample());if(sample.open)return NOOP;sample.open=true;
        sample.timer.poll();sample.timer.begin(true);long cpu=cpu(),bytes=alloc();
        return ()->{sample.timer.end();sample.thread[sample.cursor]=cpu<0?-1:(cpu()-cpu)/1e6;sample.bytes[sample.cursor]=bytes<0?-1:alloc()-bytes;sample.cursor=(sample.cursor+1)%240;sample.count=Math.min(240,sample.count+1);sample.open=false;};
    }
    private static double p95(double[] a,int n){if(n==0)return 0;var b=Arrays.copyOf(a,n);Arrays.sort(b);return b[Math.min(n-1,(int)(n*.95))];}
    public static String report(){var b=new StringBuilder();for(var e:samples.entrySet()){var s=e.getValue();b.append("\nHex ").append(e.getKey()).append(": ").append(s.timer.report()).append(String.format(Locale.ROOT,", thread CPU p95=%.3fms, allocation p95=%.0fB",p95(s.thread,s.count),p95(s.bytes,s.count)));}return b.toString();}
    public static void close(){for(var s:samples.values())s.timer.close();samples.clear();}
}
