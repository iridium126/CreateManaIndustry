package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec;
import java.util.function.ToIntFunction;

/** Narrow emission port: producers bind their outputs without owning the engine or its buffer names. */
public final class ParticleAppendTarget {
    private final ParticleBuffers pool;
    private final ToIntFunction<EmitterSpec> emitters;
    ParticleAppendTarget(ParticleBuffers pool,ToIntFunction<EmitterSpec> emitters){this.pool=pool;this.emitters=emitters;}
    public int capacity(){return pool.capacity();}
    public int emitter(EmitterSpec spec){return emitters.applyAsInt(spec);}
    public void flushHeaders(){pool.uploadDirtyEmitters();}
    public void bind(int counterSlot,int counterBinding,int particleBinding){
        pool.bindCounter(counterBinding,counterSlot);pool.bindParticleWrite(particleBinding);
    }
}
