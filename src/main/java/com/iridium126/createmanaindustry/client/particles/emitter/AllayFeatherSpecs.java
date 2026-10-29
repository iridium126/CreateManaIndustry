package com.iridium126.createmanaindustry.client.particles.emitter;

/** Finite opaque leaves and their uncollided, independently drawn white dissolution. */
public final class AllayFeatherSpecs {
    private AllayFeatherSpecs() {}
    public static final EmitterSpec FEATHER = EmitterSpec.builder()
            .type(ParticleTypes.allayFeather()).life(60, 60).speed(0, 0)
            .sizeOverLife(.15, .15, 1).gravity(0, -.3, 0).drag(0)
            .flutter(2).spin(true).spriteCount(12).collide(EmitterSpec.CollideMode.REST).build();
    public static final EmitterSpec SPARK = EmitterSpec.builder()
            .type(ParticleTypes.allayFeatherSpark()).life(.8, 1.6).speed(0, 0)
            .sizeOverLife(.035, .001, 1).acceleration(0, .25, 0).drag(.6)
            .glow(.7).colors(new float[]{1,1,1,1, 1,1,1,0}).build();
}
