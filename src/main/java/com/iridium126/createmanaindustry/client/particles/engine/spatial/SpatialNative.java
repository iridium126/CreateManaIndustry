package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import java.nio.file.Path;

/** ABI 12. Loading this class never loads CUDA; GL remains a complete backend. */
public final class SpatialNative {
    private static Path loaded;
    private static String receiptDigest="unloaded";
    public static String receiptDigest(){return receiptDigest;}
    private SpatialNative() {}
    public static synchronized void load(Path modules){
        Path path=modules.toAbsolutePath().normalize();
        if(loaded!=null){if(!loaded.equals(path))throw new IllegalStateException("Restart before replacing the RT module");return;}
        System.load(path.resolve("CmiParticleRt.dll").toString());
        if(abi()!=12)throw new IllegalStateException("Particle RT ABI 12 required");
        String receipt=capabilities();verifyCapabilities(receipt);
        try{receiptDigest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(receipt.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}
        loaded=path;
    }
    public static void verifyCapabilities(String receipt){
        try{var r=com.google.gson.JsonParser.parseString(receipt).getAsJsonObject();
            boolean probes=false;var modes=new java.util.HashSet<Integer>();for(var p:r.getAsJsonArray("probes"))modes.add(p.getAsInt());probes=modes.equals(java.util.Set.of(2,4));
            if(r.get("abi").getAsInt()!=12||r.get("packet_header_bytes").getAsInt()!=128||r.get("input_stride").getAsInt()!=64||r.get("output_stride").getAsInt()!=64||!probes||Math.abs(r.get("horizon").getAsDouble()-.1)>1e-8||!r.get("primitive").getAsString().equals("opaque-triangle-box")||!r.get("precompiled").getAsBoolean()||r.get("cpu_envelope_readback").getAsBoolean())throw new IllegalArgumentException("incompatible layout or execution contract");
        }catch(RuntimeException failure){throw new IllegalStateException("Particle RT capability receipt rejected",failure);}
    }
    public static native int abi();
    public static native long ownedBytes();
    public static native String capabilities();
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
}
