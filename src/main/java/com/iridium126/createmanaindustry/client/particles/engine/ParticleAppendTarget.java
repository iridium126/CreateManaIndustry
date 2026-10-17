package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec;
import com.iridium126.createmanaindustry.client.gpu.frame.GpuWriteReservations.Range;

/** Narrow emission port: producers bind their outputs without owning the engine or its buffer names. */
public interface ParticleAppendTarget {
    int capacity();
    int ensureEmitter(EmitterSpec spec);
    void flushHeaders();
    Range reserveOutput();
    void bind(Range reservation,int counterSlot,int counterBinding,int particleBinding);
}
