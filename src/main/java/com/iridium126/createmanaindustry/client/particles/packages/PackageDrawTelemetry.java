package com.iridium126.createmanaindustry.client.particles.packages;

import com.iridium126.createmanaindustry.client.gpu.profile.GpuPassTelemetry;

/** Package-owned telemetry facade; query storage belongs to the shared GPU mechanism. */
public final class PackageDrawTelemetry implements AutoCloseable {
    private final GpuPassTelemetry timing=new GpuPassTelemetry();
    public void begin(boolean enabled){timing.begin(enabled);}
    public void end(){timing.end();}
    public double poll(){return timing.poll();}
    public long skipped(){return timing.skipped();}
    public int pending(){return timing.pending();}
    public int cpuSamples(){return timing.cpuSamples();}
    public int gpuSamples(){return timing.gpuSamples();}
    public String report(){return timing.report();}
    @Override public void close(){timing.close();}
}
