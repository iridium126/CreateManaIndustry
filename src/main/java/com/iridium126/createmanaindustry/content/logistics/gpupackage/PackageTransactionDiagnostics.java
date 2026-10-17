package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.util.Arrays;
import java.util.Locale;

/** Server-thread opt-in phase timings. Disabled unless the developer supplies
 * -Dcmi.profilePackageTransactions=true; no client dependency or device statistics. */
public final class PackageTransactionDiagnostics {
    public enum Phase { DECODE, VALIDATE, APPLY, ENVIRONMENT_PACKET }
    private static final boolean ENABLED=Boolean.getBoolean("cmi.profilePackageTransactions");
    private static final int SAMPLES=1024;
    private static final class Metrics {
        final long[][] times=new long[Phase.values().length][SAMPLES];
        final int[] cursors=new int[Phase.values().length],counts=new int[Phase.values().length];
    }
    private static final ThreadLocal<Metrics> metrics=ThreadLocal.withInitial(Metrics::new);
    public static long begin(){return ENABLED?System.nanoTime():0;}
    public static void end(Phase phase,long start) {
        if(start==0)return;
        var data=metrics.get();int p=phase.ordinal(),at=data.cursors[p];
        data.times[p][at]=Math.max(0,System.nanoTime()-start);
        data.cursors[p]=(at+1)%SAMPLES;data.counts[p]=Math.min(SAMPLES,data.counts[p]+1);
    }
    /** Called on the owning server thread; report samples rather than presenting empty windows as zero cost. */
    public static String report() {
        if(!ENABLED)return "Package server transactions: off";
        var data=metrics.get();var result=new StringBuilder("Package server transactions (wall ms p50/p95/p99):");
        for(var phase:Phase.values()) {
            int p=phase.ordinal(),count=data.counts[p];result.append('\n').append(phase).append(": ");
            if(count==0){result.append("unmeasured");continue;}
            var values=Arrays.copyOf(data.times[p],count);Arrays.sort(values);
            result.append(String.format(Locale.ROOT,"%.3f/%.3f/%.3f (%d samples)",
                    percentile(values,.5),percentile(values,.95),percentile(values,.99),count));
        }
        return result.toString();
    }
    private static double percentile(long[] sorted,double quantile){return sorted[Math.max(0,(int)Math.ceil(sorted.length*quantile)-1)]/1e6;}
    public static void clear(){metrics.remove();}
    private PackageTransactionDiagnostics(){}
}
