package com.iridium126.createmanaindustry.client.particles.emitter;

/** Shared particle ABI. Offsets are 32-bit words; bindings and domain-specific unions live with their owners. */
public final class ParticleLayout {
    private ParticleLayout() {}
    public static final int WORD_BYTES=4, VEC4_WORDS=4;
    public static final int PARTICLE_VEC4=4, PARTICLE_WORDS=PARTICLE_VEC4*VEC4_WORDS;
    public static final int PARTICLE_BYTES=PARTICLE_WORDS*WORD_BYTES;
    public static final int HEADER_VEC4=20, HEADER_WORDS=HEADER_VEC4*VEC4_WORDS;
    public static final int HEADER_BYTES=HEADER_WORDS*WORD_BYTES;
    /** Header 17.x is MODEL animation or additive color mode; 17.z is held item or light mode. */
    public static final int HEADER_ANIMATION_COLOR=68, HEADER_SPAWN_STYLE=69, HEADER_ITEM_LIGHT=70;
    public static final int COMMAND_WORDS=12;
    public static final int COMMAND_COUNT=3, COMMAND_EMITTER=4, COMMAND_SEED=5;
    public static final int COMMAND_PREFIX=6, COMMAND_REFERENCE=7, COMMAND_PAYLOAD=8;
    /** Optional parameter record at the end of an emit batch. */
    public static final int PARAMETER_WORDS=48;
}
