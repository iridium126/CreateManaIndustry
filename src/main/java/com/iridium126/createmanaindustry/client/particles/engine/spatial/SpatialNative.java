package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import java.nio.file.Path;
import static com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.*;

/** ABI 12. Loading this class never loads CUDA; GL remains a complete backend. */
public final class SpatialNative {
    private static Path loaded;
    private static String loadedVersion="unloaded";
    public static String loadedVersion(){return loadedVersion;}
    public static long bytes(){return loaded==null?0:ownedBytes();}
    private SpatialNative() {}
    public static synchronized void load(Path modules){
        Path path=modules.toAbsolutePath().normalize();
        if(java.nio.file.Files.isDirectory(path))path=path.resolve("CmiParticleRt.dll");
        if(loaded!=null){if(!loaded.equals(path))throw new IllegalStateException("Restart before replacing the RT module");return;}
        try{if(!ParticleRtModules.VERSION.equals(PeFileVersion.read(path)))throw new IllegalStateException("Particle RT DLL version mismatch");}
        catch(java.io.IOException failure){throw new IllegalStateException("Particle RT DLL version unavailable",failure);}
        org.lwjgl.system.Library.loadSystem(System::load,System::loadLibrary,SpatialNative.class,"createmanaindustry",path.toString());
        String version=version();
        if(!ParticleRtModules.VERSION.equals(version))throw new IllegalStateException("Particle RT native version mismatch");
        if(abi()!=ABI)throw new IllegalStateException("Particle RT native ABI mismatch");
        verifyCapabilities(capabilities());
        loadedVersion=version;
        loaded=path;
    }
    public static void verifyCapabilities(String receipt){
        try{var r=com.google.gson.JsonParser.parseString(receipt).getAsJsonObject();
            boolean probes=false;var modes=new java.util.HashSet<Integer>();for(var p:r.getAsJsonArray("probes"))modes.add(p.getAsInt());probes=modes.equals(java.util.Set.of(2,4));
            if(r.get("abi").getAsInt()!=ABI||r.get("packet_header_bytes").getAsInt()!=SPATIAL_HEADER_BYTES||r.get("input_stride").getAsInt()!=SPATIAL_RECORD_BYTES||r.get("output_stride").getAsInt()!=SPATIAL_RECORD_BYTES||!probes||Math.abs(r.get("horizon").getAsDouble()-.1)>1e-8||!r.get("primitive").getAsString().equals("opaque-triangle-box")||!r.get("precompiled").getAsBoolean()||r.get("cpu_envelope_readback").getAsBoolean())throw new IllegalArgumentException("incompatible layout or execution contract");
        }catch(RuntimeException failure){throw new IllegalStateException("Particle RT capability receipt rejected",failure);}
    }
    public static native int abi();
    public static native String version();
    public static native long ownedBytes();
    public static native String capabilities();
    public static native void terrain(long worker,long scene,long revision,java.nio.ByteBuffer rows,int count);
    public static native long createBuffers(long bytes,int slots);
    public static native int[] buffers(long bridge,int slot);
    public static native void closeBuffers(long bridge);
    public static native long create(long bridge,int capacity,int terrainCapacity,int probes,long epoch,String modules);
    /** Offscreen topology control only; production always uses create(). */
    public static native long createReference(long bridge,int capacity,int terrainCapacity,int probes,long epoch,String modules);
    public static native boolean submit(long worker,int slot,long nonce,long sourceTick,long scene,int kind);
    public static native boolean submitRange(long worker,int slot,long nonce,long sourceTick,long scene,int kind,int members,int terrain);
    public static native int poll(long worker,int slot,long nonce);
    public static native int consume(long worker,int slot,long nonce);
    public static native boolean recycle(long worker,int slot,long nonce,long fence);
    public static native boolean close(long worker);
    public static native double[] timing(long worker,int slot,long nonce);
    public static native String diagnostics(long worker);
    public static native void delay(long worker,int milliseconds);
    static native long createStormDisplayBuffers(long bytes,int slots);
    static native long createStormControl(int capacity,long displayBridge,int probes);
    static native void installStormTerrain(long worker,long revision,java.nio.ByteBuffer rows,int count);
    static native boolean submitStormTick(long worker,java.nio.ByteBuffer packet,int bytes,int displaySlot,boolean timed);
    static native boolean prepareStormDisplaySlot(long worker,int slot);
    static native double[] stormTickTiming(long worker,long sequence);
    static native void delayStormControl(long worker,int milliseconds);
    static native int pollStormTick(long worker,long sequence);
    static native int consumeStormTick(long worker,long sequence);
    static native void installStormDome(long worker,float[] records,int[] keys,int[] flows);
    static native boolean copyStormDomeBatch(long worker,long sequence,java.nio.ByteBuffer destination);
    static native int copyStormFeatherImpacts(long worker,long sequence,java.nio.ByteBuffer destination);
    static native boolean recycleStormDisplay(long worker,int slot,long sequence,long fence);
    static native String stormControlDiagnostics(long worker);
    static native boolean closeStormControl(long worker);
}
