package com.iridium126.createmanaindustry.client.gpu.buffer;

import java.nio.IntBuffer;
import org.lwjgl.opengl.GL15;

/** Retiring a name also invalidates captured and active binding snapshots. */
public final class GpuBufferNames {
    private static long epoch;
    private GpuBufferNames() {}
    public static long epoch() { return epoch; }
    public static void delete(int buffer) {
        if(buffer==0)return;
        GpuBufferRetirement.retiring(buffer);epoch++;GL15.glDeleteBuffers(buffer);
    }
    public static void delete(int[] buffers) {for(int buffer:buffers)delete(buffer);}
    public static void delete(IntBuffer buffers) {for(int i=buffers.position();i<buffers.limit();i++)delete(buffers.get(i));}
}
