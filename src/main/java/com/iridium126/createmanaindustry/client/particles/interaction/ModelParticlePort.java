package com.iridium126.createmanaindustry.client.particles.interaction;
import com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec;
/** Operations on generic particle resources required by MODEL interaction. */
public interface ModelParticlePort {
    boolean available();
    int capacity();
    long currentGeneration();
    int ensureCombatEmitter(EmitterSpec spec);
}
