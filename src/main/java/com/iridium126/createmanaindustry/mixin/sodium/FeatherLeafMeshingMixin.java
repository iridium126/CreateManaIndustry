package com.iridium126.createmanaindustry.mixin.sodium;

import com.iridium126.createmanaindustry.client.particles.shaderpack.FeatherLeafMesh;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderTask;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
abstract class FeatherLeafMeshingMixin extends ChunkBuilderTask<ChunkBuildOutput> {
    protected FeatherLeafMeshingMixin(RenderSection section, int tick, Vector3dc camera) {
        super(section, tick, camera);
    }

    @WrapMethod(method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;")
    private ChunkBuildOutput cmi$captureLeaves(ChunkBuildContext context, CancellationToken cancellation,
                                               Operation<ChunkBuildOutput> original) {
        long epoch = FeatherLeafMesh.captureEpoch();
        if (epoch < 0) return original.call(context, cancellation);
        var section = this.render;
        var build = new FeatherLeafMesh.Build(section.getOriginX(), section.getOriginZ());
        FeatherLeafMesh.BUILD.set(build);
        try {
            var result = original.call(context, cancellation);
            if (result != null && !build.faces.isEmpty()) {
                ((FeatherLeafMesh.Output) result).cmi$setLeafMesh(new FeatherLeafMesh.Mesh(epoch, build.faces));
            }
            return result;
        } finally {
            FeatherLeafMesh.BUILD.remove();
        }
    }
}
