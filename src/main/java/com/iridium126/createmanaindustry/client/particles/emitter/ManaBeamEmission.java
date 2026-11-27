package com.iridium126.createmanaindustry.client.particles.emitter;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** World-space input for one local cast. The shared emitter header never changes per cast. */
public record ManaBeamEmission(Vec3 origin, Vec3 direction, float distance, Direction hitFace) {
    public static final int TYPE = 13;
    public static final float MAX_DISTANCE = 48;
    private static final int MAX_PARTICLES = 512;
    private static final int MAIN_PARTICLES = 4;

    public static final EmitterSpec SPEC = EmitterSpec.builder()
            .type(ParticleTypes.manaBeam())
            .speed(0, 0)
            .sizeOverLife(1, 1, 1)
            .build();

    public boolean stage(EmitCommandWriter writer, int emitter, double scale) {
        // Four silhouette layers, then enough detail to keep a long beam dense.
        int arcSegments = 2 * (int) Math.ceil(distance / 1.25);
        int sparks = (int) Math.ceil(8 * distance) + (hitFace == null ? 0 : 40);
        int detail = Math.min(MAX_PARTICLES - MAIN_PARTICLES, arcSegments + sparks);
        int count = MAIN_PARTICLES + (int) Math.round(detail * Math.clamp(scale, 0, 1));
        return writer.parameterized(emitter, count, (float) origin.x, (float) origin.y, (float) origin.z,
                (float) direction.x, (float) direction.y, (float) direction.z, distance, target -> {
                    target.put(hitFace == null ? 0 : hitFace.getStepX());
                    target.put(hitFace == null ? 0 : hitFace.getStepY());
                    target.put(hitFace == null ? 0 : hitFace.getStepZ());
                    for (int i = 3; i < EmitCommandWriter.PARAMETER_WORDS; i++) target.put(0);
                });
    }
}
