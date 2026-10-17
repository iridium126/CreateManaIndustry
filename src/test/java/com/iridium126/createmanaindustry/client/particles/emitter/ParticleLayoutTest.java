package com.iridium126.createmanaindustry.client.particles.emitter;

import com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParticleLayoutTest {
    @Test void sharedPoolAndHeaderAbiRemainCompatible() {
        assertEquals(64,ParticleLayout.PARTICLE_BYTES);
        assertEquals(320,ParticleLayout.HEADER_BYTES);
        assertEquals(12,ParticleLayout.COMMAND_WORDS);
        assertEquals(48,ParticleLayout.PARAMETER_WORDS);
        assertEquals(ParticleLayout.PARTICLE_BYTES,ParticleBuffers.BYTES_PER_PARTICLE);
        assertEquals(ParticleLayout.HEADER_VEC4,EmitterSpec.VEC4_PER_EMITTER);
        assertEquals(ParticleLayout.COMMAND_WORDS,EmitCommandWriter.WORDS);
        assertEquals(ParticleLayout.COMMAND_WORDS,DirectParticleBatch.WORDS);
    }

    @Test void materialUnionsUseTheDeclaredHeaderOffsets() {
        var additive=EmitterSpec.builder().shaderColorMode(5).spawnStyle(4).lightmap(true).build().packed();
        assertEquals(80,additive.length);
        assertEquals(5,additive[ParticleLayout.HEADER_ANIMATION_COLOR]);
        assertEquals(4,additive[ParticleLayout.HEADER_SPAWN_STYLE]);
        assertEquals(1,additive[ParticleLayout.HEADER_ITEM_LIGHT]);
        var model=EmitterSpec.builder().type(ParticleTypes.standard(ParticleTypes.Material.MODEL))
                .animation(EmitterSpec.Animation.HOLD).shaderColorMode(5).spawnStyle(3).build().packed();
        assertEquals(2,model[ParticleLayout.HEADER_ANIMATION_COLOR]);
        assertEquals(3,model[ParticleLayout.HEADER_SPAWN_STYLE]);
    }
}
