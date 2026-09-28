package com.iridium126.createmanaindustry.client.particles.engine;

import java.nio.FloatBuffer;
import java.util.BitSet;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Header mirror and dirty-range coalescing; no per-frame native allocations. */
final class ParticleEmitterUploads {
    private static final int WORDS = ParticleBuffers.VEC4_PER_EMITTER * 4;
    private final int capacity;
    private final float[] mirror;
    private final FloatBuffer staging;
    final BitSet dirty = new BitSet();
    ParticleEmitterUploads(int capacity) {
        this.capacity = capacity;
        this.mirror = new float[capacity * WORDS];
        this.staging = BufferUtils.createFloatBuffer(this.mirror.length);
    }
    void set(int id, float[] header) {
        if (id < 0 || id >= capacity) return;
        if (header.length != WORDS) throw new IllegalArgumentException("Emitter header must have " + WORDS + " words");
        boolean changed = false;
        for (int i=0;i<WORDS;i++)
            changed |= Float.floatToRawIntBits(header[i]) != Float.floatToRawIntBits(mirror[id*WORDS+i]);
        if (!changed) return;
        System.arraycopy(header,0,mirror,id*WORDS,WORDS);
        dirty.set(id);
    }
    void upload(int buffer) {
        if (dirty.isEmpty()) return;
        ParticleDiagnostics.INSTANCE.call();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);
        for (int first=dirty.nextSetBit(0);first>=0;) {
            int end=dirty.nextClearBit(first), offset=first*WORDS, length=(end-first)*WORDS;
            staging.clear();staging.put(mirror,offset,length).flip();
            ParticleDiagnostics.INSTANCE.upload(length*4L);
            ParticleDiagnostics.INSTANCE.call();
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,offset*4L,staging);
            dirty.clear(first,end);first=dirty.nextSetBit(end);
        }
    }
}
