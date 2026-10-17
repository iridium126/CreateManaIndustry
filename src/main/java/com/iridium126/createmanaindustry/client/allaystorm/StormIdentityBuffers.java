package com.iridium126.createmanaindustry.client.allaystorm;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.lwjgl.opengl.*;

/** Identity-indexed Storm storage, independent of the shared particle pool's capacity. */
public final class StormIdentityBuffers implements AutoCloseable {
    public static final int LIMIT = 131072;
    private int corrections, members, capacity;

    public void initialize() {
        if (capacity != 0) return;
        int initial=allocate(32);
        try {
            members=allocate(4);
            corrections=initial;
            capacity=1;
        } catch(RuntimeException | Error failure) {GL15.glDeleteBuffers(initial);throw failure;}
    }

    public void ensureMembers() {
        initialize();
        if (capacity == LIMIT) return;
        int nextCorrections = allocate(32L * LIMIT), nextMembers = 0;
        try {
            nextMembers = allocate(4L * LIMIT);
            GL15.glDeleteBuffers(corrections);
            GL15.glDeleteBuffers(members);
            corrections = nextCorrections;
            members = nextMembers;
            capacity = LIMIT;
        } catch (RuntimeException | Error failure) {
            GL15.glDeleteBuffers(nextCorrections);
            if (nextMembers != 0) GL15.glDeleteBuffers(nextMembers);
            throw failure;
        }
    }

    private static int allocate(long bytes) {
        int buffer = GL15.glGenBuffers();
        if(buffer==0)throw new IllegalStateException("Storm buffer name allocation failed");
        try {
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, bytes, GL15.GL_DYNAMIC_DRAW);
            if(GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)!=bytes)
                throw new IllegalStateException("Storm buffer storage allocation failed");
            GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER, GL30.GL_R32UI,
                    GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, (IntBuffer) null);
            return buffer;
        } catch(RuntimeException | Error failure){GL15.glDeleteBuffers(buffer);throw failure;}
    }

    public int corrections() { initialize(); return corrections; }
    public int members() { initialize(); return members; }

    public void write(int identity, FloatBuffer values) {
        if (identity < 0 || identity >= LIMIT) throw new IllegalArgumentException("Storm identity range");
        ensureMembers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, corrections);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, identity * 32L, values);
    }

    public void clearMembers() {
        if (members == 0) return;
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, members);
        GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER, GL30.GL_R32UI,
                GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, (IntBuffer) null);
    }

    @Override public void close() {
        if (corrections != 0) GL15.glDeleteBuffers(corrections);
        if (members != 0) GL15.glDeleteBuffers(members);
        corrections = members = capacity = 0;
    }
}
