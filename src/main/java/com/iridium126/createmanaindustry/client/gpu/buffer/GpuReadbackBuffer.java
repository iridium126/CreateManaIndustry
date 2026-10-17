package com.iridium126.createmanaindustry.client.gpu.buffer;

import java.nio.ByteBuffer;
import org.lwjgl.opengl.*;

/**
 * Owned coherent read mapping. The caller must prove GPU completion before reading any range.
 * ARB_buffer_storage specifies fence completion as the CPU visibility point for coherent server writes:
 * https://registry.khronos.org/OpenGL/extensions/ARB/ARB_buffer_storage.txt
 */
public final class GpuReadbackBuffer implements AutoCloseable {
    private final int size;
    private int buffer;
    private ByteBuffer mapping;

    public GpuReadbackBuffer(int bytes) {
        if(bytes<=0)throw new IllegalArgumentException("Readback capacity");
        size=bytes;
        try {
            buffer=GL15.glGenBuffers();
            if(buffer==0)throw new IllegalStateException("Readback buffer allocation failed");
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,buffer);
            int flags=GL30.GL_MAP_READ_BIT|GL44.GL_MAP_PERSISTENT_BIT|GL44.GL_MAP_COHERENT_BIT;
            GL44.glBufferStorage(GL31.GL_COPY_WRITE_BUFFER,bytes,flags);
            if(GL32.glGetBufferParameteri64(GL31.GL_COPY_WRITE_BUFFER,GL15.GL_BUFFER_SIZE)!=bytes)
                throw new IllegalStateException("Readback storage allocation failed");
            mapping=GL30.glMapBufferRange(GL31.GL_COPY_WRITE_BUFFER,0,bytes,flags);
            if(mapping==null)throw new IllegalStateException("Readback mapping failed");
        } catch(RuntimeException|Error failure) {close();throw failure;}
    }

    public int id() {
        if(buffer==0)throw new IllegalStateException("Readback storage closed");
        return buffer;
    }

    /** Render thread only; advances target, never maps, waits or calls the driver. */
    public void readCompleted(int offset,int bytes,ByteBuffer target) {
        id();
        if(offset<0||bytes<0||offset>size-bytes||target.remaining()<bytes)
            throw new IllegalArgumentException("Readback range");
        mapping.position(offset).limit(offset+bytes);
        try {target.put(mapping);com.iridium126.createmanaindustry.client.gpu.profile.GpuBudgetLedger.downloaded(bytes);}finally{mapping.clear();}
    }

    @Override public void close() {
        if(buffer!=0){GpuBufferRetirement.retiring(buffer);com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(buffer);}
        // Deletion unmaps and retains storage required by queued GPU commands.
        // Never expose the mapping: no Java reader can retain a retired pointer.
        buffer=0;mapping=null;
    }
}
