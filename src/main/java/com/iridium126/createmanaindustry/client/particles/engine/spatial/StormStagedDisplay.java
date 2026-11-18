package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames;
import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL45;
import static com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.*;

/** Render-side staging for a CUDA device other than the current GL adapter. */
final class StormStagedDisplay implements AutoCloseable {
    private static final int COLUMN_BYTES = STORM_COLUMN_MEMBERS * STORM_DISPLAY_COLUMN_BYTES;
    private static final int MAP = GL45.GL_MAP_WRITE_BIT | GL45.GL_MAP_PERSISTENT_BIT;
    private final int columnBase;
    private final int[] uploads = new int[RT_SLOTS];
    private final int[] outputs = new int[RT_SLOTS];
    final ByteBuffer[] mappings = new ByteBuffer[RT_SLOTS];

    StormStagedDisplay(int capacity) {
        columnBase = STORM_DISPLAY_HEADER_BYTES + capacity * STORM_DISPLAY_RECORD_BYTES;
        int bytes = columnBase + STORM_COLUMN_SLOTS * COLUMN_BYTES;
        try {
            for (int i = 0; i < RT_SLOTS; i++) {
                uploads[i] = GL45.glCreateBuffers();
                GL45.glNamedBufferStorage(uploads[i], bytes, MAP);
                mappings[i] = GL45.glMapNamedBufferRange(uploads[i], 0, bytes, MAP | GL45.GL_MAP_FLUSH_EXPLICIT_BIT);
                if (mappings[i] == null) throw new IllegalStateException("OpenGL Storm upload mapping failed");
                outputs[i] = GL45.glCreateBuffers();
                GL45.glNamedBufferStorage(outputs[i], bytes, 0);
            }
        } catch (RuntimeException | LinkageError failure) {
            close();
            throw failure;
        }
    }

    int buffer(int slot) { return outputs[slot]; }

    int upload(int slot, int members, long columns) {
        int bytes = STORM_DISPLAY_HEADER_BYTES + members * STORM_DISPLAY_RECORD_BYTES;
        copy(slot, 0, bytes);
        while (columns != 0) {
            int first = Long.numberOfTrailingZeros(columns);
            int count = Long.numberOfTrailingZeros(~(columns >>> first));
            int length = count * COLUMN_BYTES;
            copy(slot, columnBase + first * COLUMN_BYTES, length);
            bytes += length;
            int end = first + count;
            columns = end == Long.SIZE ? 0 : columns & (-1L << end);
        }
        return bytes;
    }

    private void copy(int slot, int offset, int bytes) {
        GL45.glFlushMappedNamedBufferRange(uploads[slot], offset, bytes);
        GL45.glCopyNamedBufferSubData(uploads[slot], outputs[slot], offset, offset, bytes);
    }

    @Override
    public void close() {
        for (int i = 0; i < RT_SLOTS; i++) {
            if (mappings[i] != null) GL45.glUnmapNamedBuffer(uploads[i]);
            GpuBufferNames.delete(uploads[i]);
            GpuBufferNames.delete(outputs[i]);
        }
    }
}
