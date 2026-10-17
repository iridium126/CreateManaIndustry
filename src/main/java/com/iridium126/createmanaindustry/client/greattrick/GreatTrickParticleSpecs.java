package com.iridium126.createmanaindustry.client.greattrick;

import com.iridium126.createmanaindustry.client.particles.emitter.*;

/** Business-owned materials shared by the two Great Trick GPU producers. */
public final class GreatTrickParticleSpecs {
    private GreatTrickParticleSpecs() {}
    public static EmitterSpec conjure() {
        return EmitterSpec.builder().shape(EmitterShape.POINT).speed(0,0).life(1,1)
                .sizeOverLife(1,1,1).colors(new float[]{1,1,1,1}).glow(1)
                .type(ParticleTypes.standard(ParticleTypes.Material.ADDITIVE))
                .shaderColorMode(5).spawnStyle(8).build();
    }
    public static EmitterSpec ribbon(boolean alpha) {
        return EmitterSpec.builder().shape(EmitterShape.POINT).speed(0,0).life(0,0)
                .sizeOverLife(1,1,1).colors(new float[]{1,1,1,1}).glow(1)
                .type(ParticleTypes.standard(alpha?ParticleTypes.Material.ALPHA:ParticleTypes.Material.ADDITIVE))
                .shaderColorMode(5).spawnStyle(9).build();
    }
}
