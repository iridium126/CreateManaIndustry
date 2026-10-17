package com.iridium126.createmanaindustry.client.gpu.buffer;

/** Render-thread notifications for captured binding snapshots. Invalidate the
 * exact name before deletion/reuse; a later restore must never recreate it. */
public final class GpuBufferRetirement {
    public interface Snapshot {
        void forgetBuffer(int buffer);
        default void forgetProgram(int program) {}
        default void forgetTexture(int texture) {}
        default void forgetVertexArray(int vertexArray) {}
    }
    private static Snapshot[] active=new Snapshot[8];
    private static int size;
    public static void capture(Snapshot snapshot){
        if(size==active.length)active=java.util.Arrays.copyOf(active,size*2);
        active[size++]=snapshot;
    }
    public static void restore(Snapshot snapshot){
        for(int i=size-1;i>=0;i--)if(active[i]==snapshot){
            System.arraycopy(active,i+1,active,i,size-i-1);active[--size]=null;return;
        }
    }
    public static void retiring(int buffer){if(buffer!=0)for(int i=0;i<size;i++)active[i].forgetBuffer(buffer);}
    public static void retiringProgram(int program){if(program!=0)for(int i=0;i<size;i++)active[i].forgetProgram(program);}
    public static void retiringTexture(int texture){if(texture!=0)for(int i=0;i<size;i++)active[i].forgetTexture(texture);}
    public static void retiringVertexArray(int vao){if(vao!=0)for(int i=0;i<size;i++)active[i].forgetVertexArray(vao);}
    private GpuBufferRetirement(){}
}
