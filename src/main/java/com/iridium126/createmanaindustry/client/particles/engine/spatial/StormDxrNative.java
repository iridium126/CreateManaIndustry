package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import static com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.ABI;

final class StormDxrNative {
    private static boolean loaded;

    private StormDxrNative() {}

    static synchronized void load() throws IOException {
        if (loaded) return;
        Path path = ParticleNativeModules.prepareDxr();
        org.lwjgl.system.Library.loadSystem(System::load, System::loadLibrary, StormDxrNative.class,
                "createmanaindustry", path.toString());
        if (!ParticleNativeModules.DXR_VERSION.equals(version()) || abi() != ABI)
            throw new IllegalStateException("Storm DXR module version or ABI mismatch");
        loaded = true;
    }

    private static native String version();
    private static native int abi();
    static native String[] devices(boolean probe);
    static native long create(int capacity, String id, ByteBuffer[] mappings, int probes, long renderLuid, int renderNodes);
    static native long[] sharedDisplayHandles(long worker, int slot);
    static native void closeSharedDisplayHandles(long resource, long fence);
    static native String gpuModel(long worker);
    static native void installStormTerrain(long worker, long revision, ByteBuffer rows, int count);
    static native boolean submitStormTick(long worker, ByteBuffer packet, int bytes, int slot, boolean timed);
    static native boolean prepareStormDisplaySlot(long worker, int slot);
    static native double[] stormTickTiming(long worker, long sequence);
    static native void delayStormControl(long worker, int milliseconds);
    static native int pollStormTick(long worker, long sequence);
    static native int consumeStormTick(long worker, long sequence);
    static native void installStormDome(long worker, float[] records, int[] keys, int[] flows);
    static native boolean copyStormDomeBatch(long worker, long sequence, ByteBuffer output);
    static native int copyStormFeatherImpacts(long worker, long sequence, ByteBuffer output);
    static native long stagedStormDisplayTime(long worker, int slot, long sequence, long[] metadata);
    static native boolean recycleStormDisplay(long worker, int slot, long sequence, long fence);
    static native String stormControlDiagnostics(long worker);
    static native boolean closeStormControl(long worker);
}
