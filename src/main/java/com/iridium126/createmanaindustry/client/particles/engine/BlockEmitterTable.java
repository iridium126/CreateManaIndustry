package com.iridium126.createmanaindustry.client.particles.engine;

import java.nio.FloatBuffer;
import java.util.BitSet;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;

/** Render/client-thread owned resident table. No GL calls from world callbacks. */
public final class BlockEmitterTable {
    public static final int CAPACITY = 262140;
    private static final int WORDS = 8;
    private final FloatBuffer staging = BufferUtils.createFloatBuffer(CAPACITY * WORDS);
    private final BitSet dirty = new BitSet();
    private final BitSet occupied = new BitSet();
    private int highWater;
    private int nextFree;
    private int buffer;
    private float maxRate;

    public int add(float x, float y, float z, float rate, int spec, float radius) {
        int slot = occupied.nextClearBit(nextFree);
        if (slot >= CAPACITY) return -1;
        occupied.set(slot);
        nextFree = slot + 1;
        highWater = Math.max(highWater, slot + 1);
        maxRate = Math.max(maxRate, rate);
        int b = slot * WORDS;
        staging.put(b, x).put(b + 1, y).put(b + 2, z).put(b + 3, rate);
        // Deterministic stagger prevents all low-rate emitters firing together.
        staging.put(b + 4, spec).put(b + 5, ((slot * 0x9e3779b9) >>> 8) * 0x1.0p-24f)
                .put(b + 6, radius).put(b + 7, 0f);
        dirty.set(slot);
        return slot;
    }

    public void remove(int slot) {
        if (slot < 0 || !occupied.get(slot)) return;
        occupied.clear(slot);
        nextFree = Math.min(nextFree, slot);
        staging.put(slot * WORDS + 3, 0f);
        dirty.set(slot);
        if (slot + 1 == highWater) highWater = occupied.length();
    }

    public int size() { return occupied.cardinality(); }
    public int dispatchSize() { return highWater; }

    /** Conservative spawn bound, without a GPU readback or per-emitter CPU loop. */
    public int spawnBound(float dt, double scale, int capacity) {
        if (dt <= 0 || scale <= 0 || highWater == 0) return 0;
        // Include the fractional accumulator and float rounding at an integer
        // boundary; this must bound every particle the GPU can append.
        float step = maxRate * (dt * (float) scale);
        return (int) Math.min(capacity, (long) highWater * ((long) Math.floor(step) + 1));
    }

    public void uploadAndBind() {
        if (buffer == 0) {
            buffer = GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long) CAPACITY * WORDS * 4, GL15.GL_DYNAMIC_DRAW);
            dirty.set(0, highWater);
        } else {
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
        }
        // Contiguous edits become one upload; unchanged GPU phase words survive.
        for (int start = dirty.nextSetBit(0); start >= 0 && start < highWater;) {
            int end = Math.min(highWater, dirty.nextClearBit(start));
            staging.limit(end * WORDS).position(start * WORDS);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, (long) start * WORDS * 4, staging);
            staging.clear();
            start = dirty.nextSetBit(end);
        }
        dirty.clear();
        // Reuse the command binding only for this independent compute pass.
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, ParticleBuffers.EMIT_BB, buffer);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
    }

    public void clear() {
        occupied.clear();
        dirty.clear();
        highWater = 0;
        nextFree = 0;
        maxRate = 0;
    }

    public void free() {
        if (buffer != 0) GL15.glDeleteBuffers(buffer);
        buffer = 0;
        clear();
    }
}
