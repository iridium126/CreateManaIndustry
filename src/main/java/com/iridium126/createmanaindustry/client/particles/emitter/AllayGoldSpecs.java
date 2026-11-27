package com.iridium126.createmanaindustry.client.particles.emitter;

/** Cached helix motion, no collision or death chain. World sizes live in the record. */
public final class AllayGoldSpecs {
    public static final int TYPE=9;
    /** Four times the historical cap; leaves headroom above the enlarged 2R field. */
    public static final int MAX_COUNT=262144;
    public static final EmitterSpec GOLD=EmitterSpec.builder().type(ParticleTypes.allayGold())
            .life(8,12).speed(0,0).sizeOverLife(1,.15,1).glow(1)
            .colors(new float[]{1,.94f,.67f,1, 1,.72f,.25f,1, .9f,.38f,.06f,0}).build();
    private AllayGoldSpecs() {}
}
