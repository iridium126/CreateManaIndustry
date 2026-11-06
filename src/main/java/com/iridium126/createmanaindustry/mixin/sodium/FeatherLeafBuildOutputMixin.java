package com.iridium126.createmanaindustry.mixin.sodium;

import com.iridium126.createmanaindustry.client.particles.shaderpack.FeatherLeafMesh;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = ChunkBuildOutput.class, remap = false)
abstract class FeatherLeafBuildOutputMixin implements FeatherLeafMesh.Output {
    @Unique private FeatherLeafMesh.Mesh cmi$leafMesh;

    @Override public FeatherLeafMesh.Mesh cmi$getLeafMesh() { return cmi$leafMesh; }
    @Override public void cmi$setLeafMesh(FeatherLeafMesh.Mesh mesh) { cmi$leafMesh = mesh; }
}
