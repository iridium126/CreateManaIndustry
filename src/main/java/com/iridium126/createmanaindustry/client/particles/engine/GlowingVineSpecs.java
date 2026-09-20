package com.iridium126.createmanaindustry.client.particles.engine;

import java.util.EnumMap;
import java.util.Map;

import com.iridium126.createmanaindustry.client.particles.emitter.EmitterShape;
import com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec;

import net.minecraft.core.Direction;

/** GPU emitter presets for the non-block-entity glowing vine effect. */
public final class GlowingVineSpecs {
    /**
     * Eight entries in the same wheel format consumed by Hex Spray's additive
     * shader path. They form a green-only wheel from deep to pale green, so a
     * uniform wheel phase never leaves the requested colour interval.
     */
    private static final float[] GREEN_WHEEL = {
            0.025f, 0.16f, 0.055f, 0.30f,
            0.055f, 0.30f, 0.095f, 0.30f,
            0.12f, 0.50f, 0.16f, 0.30f,
            0.24f, 0.72f, 0.25f, 0.30f,
            0.42f, 0.94f, 0.38f, 0.30f,
            0.24f, 0.72f, 0.25f, 0.30f,
            0.12f, 0.50f, 0.16f, 0.30f,
            0.055f, 0.30f, 0.095f, 0.30f
    };

    private static final Map<Direction, EmitterSpec> BY_FACE = new EnumMap<>(Direction.class);

    static {
        for (Direction face : Direction.values()) {
            if (face != Direction.DOWN)
                BY_FACE.put(face, create(face));
        }
    }

    public static EmitterSpec forFace(Direction face) {
        return BY_FACE.get(face);
    }

    private static EmitterSpec create(Direction face) {
        return EmitterSpec.builder()
                .shape(EmitterShape.PLANE)
                .size(0.46)
                .speed(0.04, 0.10)
                // Match Hex Spray's non-dynamic lifetime and quad size. The
                // per-particle [2/3, 4/3] scale and exponential shrink are
                // applied by the shared GPU emit/render path.
                .life(256.0 / 4.0 / 20.0, 256.0 / 3.0 / 20.0)
                .sizeOverLife(0.135, 0.135, 1.0)
                .gravity(0.0, -0.82, 0.0)
                .wind(0.30, 0.72, 0.04, 0.18)
                .drag(0.45)
                .colors(GREEN_WHEEL)
                // colorMode 6 samples the existing Hex Spray wheel with a
                // per-particle uniform phase rather than deriving colour from
                // the changing velocity direction.
                .shaderColorMode(6)
                .planeNormal(face.getStepX(), face.getStepY(), face.getStepZ())
                .material(EmitterSpec.Material.ADDITIVE)
                .collide(EmitterSpec.CollideMode.NONE)
                .glow(1.0)
                .build();
    }

    private GlowingVineSpecs() {
    }
}
