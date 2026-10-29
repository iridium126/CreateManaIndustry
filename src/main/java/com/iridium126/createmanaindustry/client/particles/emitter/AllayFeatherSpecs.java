package com.iridium126.createmanaindustry.client.particles.emitter;

/** Finite opaque leaves; expiration removes the record without children. */
public final class AllayFeatherSpecs {
    private AllayFeatherSpecs() {}
    public static final EmitterSpec FEATHER = EmitterSpec.builder()
            .type(ParticleTypes.allayFeather()).life(60, 60).speed(0, 0)
            .sizeOverLife(.15, .15, 1).gravity(0, -.3, 0).drag(0)
            .flutter(2).spin(true).spriteCount(12).collide(EmitterSpec.CollideMode.REST).build();
}
