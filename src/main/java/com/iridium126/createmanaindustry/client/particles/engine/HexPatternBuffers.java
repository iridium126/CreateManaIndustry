package com.iridium126.createmanaindustry.client.particles.engine;

import java.nio.ByteBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Separate attachment tables; the particles themselves live in the ordinary pool. */
final class HexPatternBuffers {
    static final int SLOTS = 4096;
    static final int META_ROWS = 5;
    static final int ANCHOR_BASE = 1 + SLOTS * META_ROWS;
    static final int VERTEX_STRIDE = 262144;
    static final int INPUT_BIND = 24, LIVE_BIND = 25, RESOURCE_BIND = 26, COMMAND_BIND = 27, POINT_BIND = 28;
    final ByteBuffer input = BufferUtils.createByteBuffer((ANCHOR_BASE + SLOTS) * 16);
    private int inputs, live, resources, commands, points;
    private long pointBytes;

    void ensure() {
        if (inputs != 0) return;
        inputs = allocate(input.capacity());
        live = allocate(SLOTS * 9L * 4);
        resources = allocate(256 * 16L);
        commands = allocate(SLOTS * 16L);
        points = allocate(16);
        pointBytes = 16;
        input.putFloat(0, 0);
        uploadInputs(0, 0);
    }

    void uploadInputs(int slots, int anchors) {
        input.clear();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, inputs);
        // Orphan the frame input storage: preceding draws retain their old storage, without a CPU wait.
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, input.capacity(), GL15.GL_STREAM_DRAW);
        input.limit((1 + slots * META_ROWS) * 16);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, input);
        input.clear();
        if (anchors > 0) {
            input.position(ANCHOR_BASE * 16).limit((ANCHOR_BASE + anchors) * 16);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, (long) ANCHOR_BASE * 16, input);
        }
        input.clear();
    }

    void uploadResources(ByteBuffer data) {
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, resources);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, data, GL15.GL_DYNAMIC_DRAW);
    }

    void ensurePoints(int count) {
        long needed = Math.max(16L, count * 16L);
        if (needed <= pointBytes) return;
        pointBytes = Long.highestOneBit(needed - 1) << 1;
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, points);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, pointBytes, GL15.GL_DYNAMIC_DRAW);
    }

    void begin() {
        bind();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, live);
        GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER, GL30.GL_R32UI, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, (ByteBuffer) null);
    }

    void bind() {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, INPUT_BIND, inputs);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, LIVE_BIND, live);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, RESOURCE_BIND, resources);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, COMMAND_BIND, commands);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, POINT_BIND, points);
    }

    void draw(int count) {
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, commands);
        GL43.glMultiDrawArraysIndirect(GL11.GL_TRIANGLES, 0L, count, 16);
    }

    void free() {
        for (int id : new int[]{inputs, live, resources, commands, points})
            if (id != 0) GL15.glDeleteBuffers(id);
        inputs = live = resources = commands = points = 0;
    }

    private static int allocate(long bytes) {
        int id = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, id);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, bytes, GL15.GL_DYNAMIC_DRAW);
        return id;
    }
}
