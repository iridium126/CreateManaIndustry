package com.iridium126.createmanaindustry.client.allaystorm;

import com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec;
import net.minecraft.world.phys.Vec3;

/** Render-thread operations required by Storm. No pool, program or binding IDs escape this port. */
public interface StormParticlePort {
    boolean initialized();
    void invalidateIdentities();
    int emitter(EmitterSpec spec);
    float poofEmitter();
    void header(int emitter, float[] words);
    void correction(int member, float x, float y, float z, float arrival,
                    float vx, float vy, float vz, float strength);
    void damage(int member, float amount, float kbX, float kbZ, float light, boolean died);
    void clearMembers();
    void prepareAppearance(Vec3 center);
    void collisionColumn(double x, double y, double z);
    boolean collisionWindow(double x, double y, double z, boolean prefetch);
    boolean capturePositions(int counterSlot, Vec3 anchor, int players);
    boolean captureWaveContacts(int counterSlot, int localMask, Vec3 aim, float time, float dt,
                                float[] waves, float[] targets, StarfallGpuBuffers state);
}
