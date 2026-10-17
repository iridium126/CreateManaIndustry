package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers;
import com.iridium126.createmanaindustry.client.allaystorm.StormParticlePort;
import com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

/** Engine-owned adapter. All shared-pool binding and generation choices remain with the owner. */
final class StormParticleAccess implements StormParticlePort {
    private final CMIParticleEngine owner;

    StormParticleAccess(CMIParticleEngine owner) { this.owner = owner; }

    public boolean initialized() { return owner.initialized(); }
    public void invalidateIdentities() { owner.invalidateParticleIdentities(); }
    public int emitter(EmitterSpec spec) { return owner.ensureEmitter(spec); }
    public float poofEmitter() { return owner.ensurePoofEmitter(); }
    public void header(int emitter, float[] words) { owner.gpu().setEmitterHeader(emitter, words); }
    public void correction(int member, float x, float y, float z, float arrival,
                           float vx, float vy, float vz, float strength) {
        owner.gpu().writeCorrection(member, x, y, z, arrival, vx, vy, vz, strength);
    }
    public void damage(int member, float amount, float kbX, float kbZ, float light, boolean died) {
        owner.enqueueDamageEntry(member, amount, kbX, kbZ, light,
                ParticleBuffers.DAMAGE_FLAG_MEMBER | (died ? ParticleBuffers.DAMAGE_FLAG_DIED : 0));
    }
    public void clearMembers() { owner.gpu().clearMemberMap(); }
    public void prepareAppearance(Vec3 center) {
        owner.allayAtlas().ensureLoaded();
        owner.collisionBake().ensureQuadrants(center);
    }
    public void collisionColumn(double x, double y, double z) { owner.collisionBake().ensureColumn(x, y, z); }
    public boolean collisionWindow(double x, double y, double z, boolean prefetch) {
        return owner.collisionBake().helixWindow(x, y, z, prefetch);
    }

    public boolean capturePositions(int counterSlot, Vec3 anchor, int players) {
        int program = owner.programs().stormPos();
        if (program == 0) return false;
        var pool = owner.gpu();
        pool.clearStormPosCount();
        GL20.glUseProgram(program);
        pool.bindParticleWrite(ParticleBuffers.PARTICLE_BB_WRITE);
        pool.bindParticleRead(ParticleBuffers.PARTICLE_BB_READ);
        pool.bindMemberMap();
        pool.bindCounter(3, counterSlot);
        pool.bindEmitters(5);
        pool.bindPlayers();
        pool.bindStormPos();
        pool.bindPopulationRead(program, true, owner.currentGeneration() + 1);
        CMIParticleEngine.setFloatUniform(program, "uAnchor", (float) anchor.x, (float) anchor.y, (float) anchor.z);
        CMIParticleEngine.setIntUniform(program, "uPlayerCount", players);
        pool.dispatch(ParticleBuffers.STORM_DISPATCH);
        feedbackBarrier();
        return true;
    }

    public boolean captureWaveContacts(int counterSlot, int localMask, Vec3 aim, float time, float dt,
                                       float[] waves, float[] targets, StarfallGpuBuffers state) {
        int program = owner.programs().waveContact();
        if (program == 0) return false;
        var pool = owner.gpu();
        GL20.glUseProgram(program);
        pool.bindParticleWrite(ParticleBuffers.PARTICLE_BB_WRITE);
        pool.bindCounter(3, counterSlot);
        pool.bindEmitters(5);
        pool.bindWaveContact();
        bindWaves(state, program, true);
        pool.bindPopulationRead(program, true, owner.currentGeneration() + 1);
        CMIParticleEngine.setUIntUniform(program, "uLocalWaveMask", localMask);
        CMIParticleEngine.setFloatUniform(program, "uLocalPlayerPos", (float) aim.x, (float) aim.y, (float) aim.z);
        CMIParticleEngine.setFloatUniform(program, "uTimeSec", time);
        CMIParticleEngine.setFloatUniform(program, "uDt", dt);
        CMIParticleEngine.setVec4ArrayUniform(program, "uWave", waves);
        CMIParticleEngine.setVec4ArrayUniform(program, "uWaveTarget", targets);
        pool.dispatch(ParticleBuffers.STORM_DISPATCH);
        feedbackBarrier();
        return true;
    }

    static void bindWaves(StarfallGpuBuffers state, int program, boolean staged) {
        boolean enabled=state.bind(staged);
        if(program!=0)org.lwjgl.opengl.GL41.glProgramUniform1i(program,
                CMIParticleEngine.loc(program,"uStarfallEnabled"),enabled?1:0);
    }

    private static void feedbackBarrier() {
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_ATOMIC_COUNTER_BARRIER_BIT);
    }
}
