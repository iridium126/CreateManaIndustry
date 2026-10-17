package com.iridium126.createmanaindustry.client.gpu.buffer;

import com.iridium126.createmanaindustry.client.gpu.GpuCapabilities;

import java.nio.IntBuffer;
import java.util.ArrayDeque;
import org.lwjgl.opengl.*;

/** Lazily allocated storage. Growth preserves GPU-owned words; retirement never waits. */
public final class GpuGrowingBuffer implements AutoCloseable {
    private record Retired(int buffer, long fence) {}
    private final ArrayDeque<Retired> retired = new ArrayDeque<>();
    private int buffer;
    private long bytes;

    public int ensure(long requested) {
        if (requested < 0) throw new IllegalArgumentException("Negative GPU capacity");
        poll();
        if (buffer != 0 && requested <= bytes) return buffer;
        long capacity = 16;
        while (capacity < requested) capacity = Math.multiplyExact(capacity, 2);
        if (capacity > GpuCapabilities.current().storageBlockBytes())
            throw new IllegalStateException("GPU buffer exceeds device storage limit");
        int next = GL15.glGenBuffers();
        if(next==0)throw new IllegalStateException("GPU buffer name allocation failed");
        long fence = 0;
        try {
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, next);
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.allocate(GL43.GL_SHADER_STORAGE_BUFFER, capacity, GL15.GL_DYNAMIC_DRAW);
            if(GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)!=capacity)
                throw new IllegalStateException("GPU buffer storage allocation failed");
            GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER, GL30.GL_R32UI,
                    GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, (IntBuffer) null);
            if (buffer != 0) {
                GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, buffer);
                GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, next);
                GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, 0, 0, bytes);
                fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                if (fence == 0) throw new IllegalStateException("GPU growth fence allocation failed");
                retired.addLast(new Retired(buffer, fence));
            }
            buffer = next;
            bytes = capacity;
            return buffer;
        } catch (RuntimeException | Error failure) {
            if (fence != 0) GL32.glDeleteSync(fence);
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(next);
            throw failure;
        }
    }

    public void poll() {
        while (!retired.isEmpty()) {
            var first = retired.peekFirst();
            int status = GL32.glClientWaitSync(first.fence, 0, 0);
            if (status == GL32.GL_TIMEOUT_EXPIRED) return;
            if (status == GL32.GL_WAIT_FAILED) throw new IllegalStateException("GPU resource retirement failed");
            GL32.glDeleteSync(first.fence);
            GpuBufferRetirement.retiring(first.buffer);
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(first.buffer);
            retired.removeFirst();
        }
    }

    /** Internal allocation accounting; this is not a device-residency measurement. */
    public long capacityBytes(){return bytes;}

    @Override public void close() {
        if (buffer != 0){GpuBufferRetirement.retiring(buffer);com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(buffer);}
        for (var entry : retired) {
            GL32.glDeleteSync(entry.fence);
            GpuBufferRetirement.retiring(entry.buffer);
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(entry.buffer);
        }
        retired.clear();
        buffer = 0;
        bytes = 0;
    }
}
