package com.iridium126.createmanaindustry.client.gpu.profile;

/** Diagnostic fan-out only. Observations do not debit any GPU budget. Render-thread ownership. */
public final class GpuTimingObserver {
    @FunctionalInterface public interface Sink {void sample(String label,long frame,long generation,double cpuMillis,double gpuMillis);}
    private static Sink sink;
    private GpuTimingObserver(){}
    public static void install(Sink value){if(value!=null&&sink!=null&&sink!=value)throw new IllegalStateException("GPU timing observer already installed");sink=value;}
    public static boolean active(){return sink!=null;}
    public static void sample(String label,long frame,long generation,double cpu,double gpu){if(sink!=null&&label!=null&&frame>=0)sink.sample(label,frame,generation,cpu,gpu);}
}
