package com.iridium126.createmanaindustry.client.particles.engine;

import java.nio.ByteBuffer;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuReadbackBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Independent staging storage: simulation counters may be reused while a snapshot is pending. */
final class ParticleReadbacks {
    static final int STORM_OFFSET = 48;
    static final int WAVE_OFFSET = STORM_OFFSET + 4 + ParticleBuffers.STORMPOS_CAP * ParticleBuffers.STORMPOS_ENTRY_FLOATS * 4;
    static final int FEATHER_REQUEST_OFFSET = WAVE_OFFSET + 4 + ParticleBuffers.WAVECONTACT_CAP * ParticleBuffers.WAVECONTACT_ENTRY_FLOATS * 4;
    static final int GOLD_SURFACE_OFFSET=FEATHER_REQUEST_OFFSET + com.iridium126.createmanaindustry.client.particles.emitter.AllayFeatherLayout.READBACK_BYTES;
    static final int BYTES = GOLD_SURFACE_OFFSET+StormSurfaceCache.FEEDBACK_BYTES;
    static final class Slot {
        int buffer;
        GpuReadbackBuffer storage;
        long fence, generation, epoch, gameTime;
        boolean positions, waves,published,feathers,featherRequests,goldSurface;
        long goldSurfaceEpoch;
        ByteBuffer goldData;
        final ByteBuffer featherData=BufferUtils.createByteBuffer(com.iridium126.createmanaindustry.client.particles.emitter.AllayFeatherLayout.READBACK_BYTES);
        com.iridium126.createmanaindustry.content.allaystorm.StormIdentity positionIdentity;
        ParticleHitRay hitRay;
        final ByteBuffer data = BufferUtils.createByteBuffer(BYTES);
    }
    private final Slot[] slots = {new Slot(), new Slot(), new Slot(), new Slot()};
    Slot freeSlot() {
        for (Slot slot : slots) if (slot.fence == 0) {
            if (slot.buffer == 0) {
                slot.storage = new GpuReadbackBuffer(BYTES);
                slot.buffer = slot.storage.id();
                GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
            }
            return slot;
        }
        return null;
    }
    Slot completed() {
        Slot oldest = null;
        for (Slot slot : slots)
            if (slot.fence != 0 && slot.published && (oldest == null || slot.generation < oldest.generation)) oldest = slot;
        if (oldest == null) return null;
        int state = GL32.glClientWaitSync(oldest.fence, 0, 0);
        if (state == GL32.GL_TIMEOUT_EXPIRED) return null;
        if (state == GL32.GL_WAIT_FAILED) {
            release(oldest);
            throw new IllegalStateException("Particle readback fence failed; failed slot retired");
        }
        oldest.data.clear();
        oldest.data.limit(oldest.waves ? FEATHER_REQUEST_OFFSET : oldest.positions ? WAVE_OFFSET : STORM_OFFSET);
        oldest.storage.readCompleted(0,oldest.data.remaining(),oldest.data);
        oldest.data.position(0);
        if(oldest.featherRequests){oldest.featherData.clear();oldest.storage.readCompleted(FEATHER_REQUEST_OFFSET,oldest.featherData.remaining(),oldest.featherData);oldest.featherData.position(0);}
        if(oldest.goldSurface){if(oldest.goldData==null)oldest.goldData=BufferUtils.createByteBuffer(StormSurfaceCache.FEEDBACK_BYTES);oldest.goldData.clear();oldest.storage.readCompleted(GOLD_SURFACE_OFFSET,oldest.goldData.remaining(),oldest.goldData);oldest.goldData.position(0);}
        return oldest;
    }
    void release(Slot slot) {
        GL32.glDeleteSync(slot.fence);
        slot.fence = 0;slot.published=false;
    }
    void clear() {
        for (Slot slot : slots) if (slot.fence != 0) release(slot);
    }
    void close() {
        clear();
        for (Slot slot : slots) if (slot.buffer != 0) {
            slot.storage.close(); slot.storage=null; slot.buffer = 0;
        }
    }
    static float[] entries(ByteBuffer data, int offset, int capacity, int stride) {
        int count = Math.min(capacity, Math.max(0, data.getInt(offset)));
        if (count == 0) return null;
        float[] result = new float[count * stride];
        for (int i = 0; i < result.length; i++) result[i] = data.getFloat(offset + 4 + i * 4);
        return result;
    }
}
