package com.iridium126.createmanaindustry.content.logistics.gpupackage;

/** Wall-time budgets expressed in world ticks; physical integration remains 50 ms per game tick. */
public final class PackageTickTiming {
    public static final java.util.function.DoubleSupplier DEFAULT_RATE=()->20;
    private PackageTickTiming(){}
    public static double rate(double rate){
        if(!Double.isFinite(rate)||rate<=0)throw new IllegalArgumentException("Package tick rate");
        return Math.clamp(rate,1,10000);
    }
    public static long periodNanos(double rate){return Math.max(1,Math.round(1_000_000_000.0/rate(rate)));}
    public static int historyTicks(double rate){return (int)Math.ceil(Math.max(20,rate(rate)));}
    public static int stepsPerFrame(double rate){return (int)Math.ceil(4*Math.max(1,rate(rate)/20));}
    public static long deadlineTicks(long normalTicks,double rate){return (long)Math.ceil(normalTicks*Math.max(1,rate(rate)/20));}
}
