package com.iridium126.createmanaindustry.client.particles.engine;


import com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec;
import com.iridium126.createmanaindustry.client.particles.emitter.ParticleTypes;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** Stable public emission API. Requests are admitted and written here. */
public final class CMIParticleEngine {
    public static final CMIParticleEngine INSTANCE=new CMIParticleEngine();
    private CMIParticleEngine(){}


    /** Handle for one live stream; the stream is removed with {@link #removeStream(StreamHandle)}. */
    public record StreamHandle(long id) {
    }

    /**
     * Client-side Hexcasting conjure spray (spawnStyle 4): the vanilla
     * {@code MsgCastParticleS2C.Handler} distribution — {@code vel} in
     * blocks/s, {@code fuzziness} the spawn-sphere radius, {@code spread} the
     * cone half-angle in radians, {@code wheelRGBA} one wheel of 8 sampled
     * pigment colors ({@link HexSpecs#sampleWheel}). The exact per-particle
     * sampling happens GPU-side in emit.comp at the next frame packing.
     */
    public void spawnHexSpray(Vec3 pos, Vec3 vel, double fuzziness, double spread, int count, float[] wheelRGBA) {
        var r=ParticleRuntime.INSTANCE;
        trySpawnHexSpray(pos,vel,fuzziness,spread,count,
                com.iridium126.createmanaindustry.client.hex.particles.HexPigmentData.wheel(wheelRGBA));
    }

    public boolean trySpawnHexSpray(Vec3 pos,Vec3 vel,double fuzziness,double spread,int count,
            com.iridium126.createmanaindustry.client.hex.particles.HexPigmentData pigment) {
        var r=ParticleRuntime.INSTANCE;
        return trySpawnHexSpray(pos,vel,fuzziness,spread,count,pigment,null);
    }

    public boolean trySpawnHexSpray(Vec3 pos,Vec3 vel,double fuzziness,double spread,int count,
            com.iridium126.createmanaindustry.client.hex.particles.HexPigmentData pigment,Runnable recovery) {
        var r=ParticleRuntime.INSTANCE;
        if(!r.available()||!ClientConfig.particleEnabled||pigment==null||!RenderSystem.isOnRenderThread()
                ||Minecraft.getInstance().level==null||r.hexSprays.size()>=ParticleBuffers.MAX_EMIT_COMMANDS)return false;
        if(!Double.isFinite(fuzziness)||!Double.isFinite(spread)||fuzziness<0||spread<0)return false;
        if(count<=0)return true;
        if(count>r.gpu.capacity()||!Float.isFinite((float)fuzziness)||!Float.isFinite((float)spread)
                ||!Float.isFinite((float)pos.x)||!Float.isFinite((float)pos.y)||!Float.isFinite((float)pos.z)
                ||!Float.isFinite((float)vel.x)||!Float.isFinite((float)vel.y)||!Float.isFinite((float)vel.z))return false;
        if(r.sprayHexEmitter<0){
            r.sprayHexEmitter=r.ensureEmitter(HexSpecs.spraySpec());
            if(r.sprayHexEmitter<0)return false;
            r.gpu.setEmitterHeader(r.sprayHexEmitter,HexSpecs.packedSprayHeader());
        }
        var world=Minecraft.getInstance().level;
        if(r.hexInputWorld!=world){r.hexParticles.clear();r.hexSprays.clear();r.hexInputWorld=world;}
        r.hexSprays.add(new ParticleRuntime.HexSpray((float)pos.x,(float)pos.y,(float)pos.z,(float)vel.x,(float)vel.y,(float)vel.z,
                (float)fuzziness,(float)spread,count,pigment,recovery,r.hexTick+1));
        return true;
    }

    /**
     * Queues one direct Hexcasting {@code conjure_particle}. The incoming
     * velocity is the exact vanilla constructor value in blocks/tick; the GPU
     * simulation uses blocks/second, so conversion happens once here. Vanilla
     * assigns gravity only when ALL THREE constructor components are non-zero.
     */
    public void spawnHexParticle(Vec3 pos, Vec3 velocityPerTick, int color) {
        var r=ParticleRuntime.INSTANCE;
        if(!trySpawnHexParticle(pos.x,pos.y,pos.z,velocityPerTick.x,velocityPerTick.y,velocityPerTick.z,color)) {
            var world=Minecraft.getInstance().level;
            if(world!=null)r.replayHexParticle(world,pos.x,pos.y,pos.z,velocityPerTick.x,velocityPerTick.y,velocityPerTick.z,color);
        }
    }

    public boolean trySpawnHexParticle(double x,double y,double z,double vx,double vy,double vz,int color) {
        var r=ParticleRuntime.INSTANCE;
        if(r.replayingHex||!r.available()||!ClientConfig.particleEnabled||!RenderSystem.isOnRenderThread())return false;
        var world=Minecraft.getInstance().level;
        if(world==null)return false;
        if(r.hexInputWorld!=world){r.hexParticles.clear();r.hexSprays.clear();r.hexInputWorld=world;}
        if(r.directHexEmitter<0){
            r.directHexEmitter=r.ensureEmitter(HexSpecs.directSpec());
            if(r.directHexEmitter<0)return false;
            r.gpu.setEmitterHeader(r.directHexEmitter,HexSpecs.packedDirectHeader(HexSpecs.directSpec()));
        }
        return r.hexParticles.offer(x,y,z,vx,vy,vz,color,r.hexTick+1);
    }

    // ------------------------------------------------------------------
    // Public API (any client thread; GPU work deferred to the render thread)
    // ------------------------------------------------------------------

    /** Fires {@code count} particles at {@code origin} (throttle applied). */
    public void spawn(EmitterSpec spec, Vec3 origin, int count) {
        var r=ParticleRuntime.INSTANCE;
        if (count <= 0)
            return;
        r.pending.add(new ParticleRuntime.Burst(spec, origin, count));
    }

    /**
     * Streams {@code rate} particles/second for {@code seconds}; {@code seconds <= 0}
     * creates an infinite stream that remains live until {@link #removeStream(StreamHandle)}
     * or {@link #clear()} is called.
     */
    public StreamHandle stream(EmitterSpec spec, Vec3 origin, double rate, double seconds) {
        var r=ParticleRuntime.INSTANCE;
        StreamHandle handle = new StreamHandle(r.nextStreamId.getAndIncrement());
        r.pending.add(new ParticleRuntime.StreamReq(handle, spec, origin, rate, seconds));
        return handle;
    }

    /** Stops one stream without removing particles that it has already emitted. */
    public void removeStream(StreamHandle handle) {
        var r=ParticleRuntime.INSTANCE;
        if (handle != null)
            r.pending.add(new ParticleRuntime.StreamCancel(handle));
    }

    /**
     * Live-switches the animation of every emitter created from {@code spec} —
     * including already-alive particles (the header re-uploads next frame).
     * Fire-and-forget like {@link #spawn}; applied on the render thread.
     */
    public void setAnimation(EmitterSpec spec, EmitterSpec.Animation animation) {
        var r=ParticleRuntime.INSTANCE;
        r.pending.add(new ParticleRuntime.AnimReq(spec, animation));
    }

    /** Drops all live particles and clears streams. */
    public void clear() {
        var r=ParticleRuntime.INSTANCE;
        r.pending.add(Boolean.TRUE);
    }

}
