/** Explicit offline-only load. Ordinary builds and clients never initialize CUDA/OptiX. */
public final class NeighborNative {
    private NeighborNative(){}
    public static native long create(int input,int output,int count,int queries,int capacity,float radius,String cuda,String optix,String source,String crt);
    public static native float run(long handle,int backend);
    public static native void destroy(long handle);
    public static native long[] memory(long handle);
}
