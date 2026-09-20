package com.iridium126.createmanaindustry.client.particles;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import com.iridium126.createmanaindustry.CMIBlocks;
import com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec;
import com.iridium126.createmanaindustry.client.particles.engine.GlowingVineSpecs;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Register client-side additive effects before chunk discovery (e.g. client setup). */
public final class BlockParticleEmitters {
    public record Source(EmitterSpec spec, float x, float y, float z, float rate, float cullRadius) {
        public Source {
            if (spec == null || spec.material != EmitterSpec.Material.ADDITIVE
                    || spec.collideMode != EmitterSpec.CollideMode.NONE || spec.lightmap)
                throw new IllegalArgumentException("Block emitters require additive, non-colliding, fullbright specs");
            if (!Float.isFinite(rate) || rate <= 0 || rate > 1024
                    || !Float.isFinite(cullRadius) || cullRadius < 0
                    || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
                throw new IllegalArgumentException("Invalid block emitter parameters (rate must be in (0, 1024])");
        }
    }

    private static final Map<Block, BiConsumer<BlockState, java.util.function.Consumer<Source>>> TYPES =
            new IdentityHashMap<>();
    private static boolean defaultsRegistered;

    public static void register(Block block, BiConsumer<BlockState, java.util.function.Consumer<Source>> sources) {
        TYPES.put(java.util.Objects.requireNonNull(block), java.util.Objects.requireNonNull(sources));
    }

    static void registerDefaults() {
        if (defaultsRegistered) return;
        defaultsRegistered = true;
        Source[] faces = new Source[Direction.values().length];
        for (Direction face : Direction.values()) {
            if (face == Direction.DOWN) continue;
            faces[face.ordinal()] = new Source(GlowingVineSpecs.forFace(face),
                    0.5f + face.getStepX() * 0.452f, 0.5f + face.getStepY() * 0.452f,
                    0.5f + face.getStepZ() * 0.452f, 100f, 8f);
        }
        register(CMIBlocks.GLOWING_VINE.get(), (state, sink) -> {
            for (Direction face : Direction.values())
                if (face != Direction.DOWN && state.getValue(VineBlock.getPropertyForFace(face)))
                    sink.accept(faces[face.ordinal()]);
        });
    }

    static boolean matches(BlockState state) { return TYPES.containsKey(state.getBlock()); }

    static void collect(BlockState state, java.util.function.Consumer<Source> sink) {
        var factory = TYPES.get(state.getBlock());
        if (factory != null) factory.accept(state, sink);
    }

    private BlockParticleEmitters() {}
}
