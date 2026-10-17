package com.iridium126.createmanaindustry.client.packages.render;


import com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec;
/** Generic resource admission; package clocks and transactions stay in their owners. */
public interface PackageParticlePort {
    boolean available();
    int capacity();
    int ensureEmitter(EmitterSpec spec);
    long importPackages(PackagePoolGpu pool,int emitter,float[] frustum,net.minecraft.client.Camera camera);
}
