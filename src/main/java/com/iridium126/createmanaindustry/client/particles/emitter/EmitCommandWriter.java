package com.iridium126.createmanaindustry.client.particles.emitter;

import java.nio.FloatBuffer;
import java.util.Arrays;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;

/** Bounded continuous staging. Every producer initializes a complete row through this owner. */
public final class EmitCommandWriter {
    public static final int LIMIT = 256, WORDS = ParticleLayout.COMMAND_WORDS, PARAMETER_WORDS = ParticleLayout.PARAMETER_WORDS;
    /** Per-command immutable input, encoded after the compact command table. */
    @FunctionalInterface public interface Parameters { void write(FloatBuffer target); }

    private final float[] rows;
    private final Parameters[] parameters;
    private final FloatBuffer staging;
    private int size;
    private long particles;

    public EmitCommandWriter() { this(LIMIT); }
    public EmitCommandWriter(int capacity) {
        if (capacity <= 0 || capacity > LIMIT) throw new IllegalArgumentException("Command capacity");
        rows = new float[capacity * WORDS];
        parameters = new Parameters[capacity];
        staging = BufferUtils.createFloatBuffer(capacity * (WORDS + PARAMETER_WORDS));
    }

    public int size() { return size; }
    public int capacity() { return parameters.length; }
    public boolean full() { return size == capacity(); }
    public int particles() { return Math.toIntExact(particles); }
    public void clear() {
        Arrays.fill(parameters, 0, size, null);
        size = 0;
        particles = 0;
    }

    public boolean basic(int emitter, int count, Vec3 origin, float light) {
        return append(emitter, count, origin.x, origin.y, origin.z, 0, light, 0, 0, 0, null);
    }
    public boolean tracked(int emitter, int count, int token, int memberKey, float light) {
        return append(emitter, count, 0, 0, 0, Float.intBitsToFloat(token), light, 0, memberKey, 0, null);
    }
    public boolean members(int emitter, int count, Vec3 center, int memberBase, boolean ring) {
        return append(emitter, count, center.x, center.y, center.z, 0, 0, memberBase, 0, ring ? 1 : 0, null);
    }
    public boolean ring(int emitter, int wave, int slot, int column) {
        return append(emitter, 128, 0, 0, 0, 0, wave, slot, column, 1, null);
    }
    public boolean parameterized(int emitter, int count, float x, float y, float z,
                                 float vx, float vy, float vz, float packed, Parameters input) {
        return append(emitter, count, x, y, z, 0, vx, vy, vz, packed,
                java.util.Objects.requireNonNull(input));
    }

    private boolean append(int emitter, int count, double x, double y, double z, float reference,
                           float c0, float c1, float c2, float c3, Parameters input) {
        // Float count/prefix must remain exact; total work is capped before upload.
        if (full() || emitter < 0 || count <= 0 || count > (1 << 24)
                || particles + count > Integer.MAX_VALUE) return false;
        if (!Float.isFinite((float)x) || !Float.isFinite((float)y) || !Float.isFinite((float)z)) return false;
        int b = size * WORDS;
        rows[b] = (float)x; rows[b+1] = (float)y; rows[b+2] = (float)z; rows[b+ParticleLayout.COMMAND_COUNT] = count;
        rows[b+ParticleLayout.COMMAND_EMITTER] = emitter; rows[b+ParticleLayout.COMMAND_SEED] = 0; rows[b+ParticleLayout.COMMAND_PREFIX] = 0; rows[b+ParticleLayout.COMMAND_REFERENCE] = reference;
        rows[b+8] = c0; rows[b+9] = c1; rows[b+10] = c2; rows[b+11] = c3;
        parameters[size++] = input;
        particles += count;
        return true;
    }

    /** Legacy proportional launch bound, including whole-column ring reservations. Domain admission is handled separately. */
    public void limitParticles(int capacity, boolean atomicRings) {
        if (capacity < 0) throw new IllegalArgumentException("Particle capacity");
        if (particles <= capacity) return;
        double scale = (double)capacity / particles;
        int write = 0, oldSize = size;
        particles = 0;
        for (int read = 0; read < oldSize; read++) {
            int b = read * WORDS;
            int count = (int)(rows[b+ParticleLayout.COMMAND_COUNT] * scale);
            if (atomicRings && parameters[read] == null && rows[b+11] == 1 && rows[b+ParticleLayout.COMMAND_COUNT] == 128
                    && rows[b+8] > 0 && rows[b+10] >= 0) count = count >= 128 ? 128 : 0;
            if (count == 0) continue;
            if (read != write) {
                System.arraycopy(rows, b, rows, write * WORDS, WORDS);
                parameters[write] = parameters[read];
            }
            rows[write * WORDS+3] = count;
            particles += count;
            write++;
        }
        Arrays.fill(parameters, write, oldSize, null);
        size = write;
    }

    public FloatBuffer encode(int frameSeed) {
        staging.clear();
        int prefix = 0, parameterIndex = 0;
        for (int i = 0; i < size; i++) {
            int b = i * WORDS;
            rows[b+ParticleLayout.COMMAND_SEED] = (((long)frameSeed * 2654435761L + i * 73856093L) >>> 8) % 1_000_000 * .000001f;
            rows[b+ParticleLayout.COMMAND_PREFIX] = prefix;
            if (parameters[i] != null) rows[b+ParticleLayout.COMMAND_REFERENCE] = parameterIndex++;
            staging.put(rows, b, WORDS);
            prefix += (int)rows[b+ParticleLayout.COMMAND_COUNT];
        }
        for (int i = 0; i < size; i++) if (parameters[i] != null) {
            int start = staging.position();
            parameters[i].write(staging);
            if (staging.position() != start + PARAMETER_WORDS)
                throw new IllegalStateException("Invalid emit parameter layout");
        }
        return staging.flip();
    }
}
