package com.iridium126.createmanaindustry.client.particles.engine.hardware;

import java.nio.file.Path;

/** R7 optional native ABI. Class loading never loads CUDA or compiles an experimental program. */
public final class HardwareNative {
    private static boolean loaded;
    private static String loadedDigest;
    private HardwareNative(){}
    public static synchronized void load(Path directory){
        String digest;try{digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(directory.resolve("CmiHardwareOffload.dll"))));}catch(Exception failure){throw new IllegalStateException("Cannot identify optional R7 native module",failure);}
        if(loaded){if(!digest.equals(loadedDigest))throw new IllegalStateException("A different R7 native module is already loaded; restart the client before switching diagnostic distributions");return;}
        for(String file:new String[]{"nvrtc-builtins64_128.dll","nvrtc64_120_0.dll","CmiHardwareOffload.dll"})System.load(directory.resolve(file).toAbsolutePath().toString());
        int abi;try{abi=nativeAbiVersion();}catch(UnsatisfiedLinkError old){throw new IllegalStateException("R8 native ABI unavailable in old module; install matching runtime and restart client",old);}if(abi!=8)throw new IllegalStateException("R8 native ABI mismatch; install matching diagnostic runtime and restart client");loadedDigest=digest;loaded=true;
    }
    public static boolean loaded(){return loaded;}
    public static String digest(){return loaded?loadedDigest:"unavailable";}
    public static native int nativeAbiVersion();
    public static native void configureAsyncQuery(long handle,int policy,int pageBytes);
    public static native int waitAsyncQuery(long handle,int slot,long nonce,int milliseconds);
    public static native double[] asyncQueryTrace(long handle,int slot,long nonce);
    public static native String probe();
    public static native String mediaProbe();
    public static native long createQuery(int input,int output,int points,int queries,int capacity,float radius,String cuda,String optix,String source,String crt);
    public static native long createQueryRequests(int input,int output,int requests,int owners,int points,int queries,int capacity,float maxRadius,String cuda,String optix,String source,String crt);
    /** GPU total, completed wall, preparation, BVH maintenance, count, scan, write (ms). */
    public static native double[] runQuery(long handle,int backend,boolean refit);
    public static native long[] queryMemory(long handle);
    public static native void closeQuery(long handle);
    public static native long createCopy(long bytes,int slots);
    public static native int[] copyBuffers(long handle,int slot);
    public static native String copyInfo(long handle);
    public static native boolean submitCopy(long handle,int slot,long bytes);
    /** Isolated concurrency probe: defer the GL GPU wait until the first consumer. */
    public static native boolean beginCopy(long handle,int slot,long bytes);
    public static native void awaitCopy(long handle,int slot);
    public static native double copyMillis(long handle,int slot);
    public static native boolean copyReady(long handle,int slot);
    public static native byte[] readCopyCuda(long handle,int slot,int bytes);
    public static native void closeCopy(long handle);
    public static native long createAsyncQuery(long bridge,int pointCapacity,int queryCapacity,int capacity,long resourceEpoch,String cuda,String optix,String source,String crt);
    public static native long createAsyncQueryForBackend(long bridge,int pointCapacity,int queryCapacity,int capacity,long resourceEpoch,int backend,String cuda,String optix,String source,String crt);
    public static native boolean submitAsyncQuery(long handle,int slot,long nonce,int backend,boolean refit);
    /** 0 free, 1 pending, 2 ready, 3 complete reference fallback, 4 quarantined, 5 consumed. */
    public static native int pollAsyncQuery(long handle,int slot,long nonce);
    public static native String asyncQueryError(long handle,int slot,long nonce);
    public static native double[] asyncQueryTiming(long handle,int slot,long nonce);
    /** Same ticket only: points, queries, input live, CSR capacity, phase, epoch, overflow. -1 means unavailable. */
    public static native long[] asyncQueryStats(long handle,int slot,long nonce);
    public static native void consumeAsyncQuery(long handle,int slot,long nonce);
    public static native boolean recycleAsyncQuery(long handle,int slot,long nonce,long glConsumerFence);
    /** False retains the worker, its bridge lease and all unretired resources. Retry after retirement. */
    public static native boolean closeAsyncQuery(long handle);
    /** Isolated fault regression only; bounded host callback delay, no GPU spin. */
    public static native void injectAsyncQueryDelay(long handle,int delayMillis,int timeoutMillis);
}
