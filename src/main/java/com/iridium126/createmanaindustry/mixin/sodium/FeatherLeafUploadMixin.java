package com.iridium126.createmanaindustry.mixin.sodium;

import com.iridium126.createmanaindustry.client.particles.shaderpack.FeatherLeafMesh;
import java.util.Collection;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderRegionManager.class, remap = false)
abstract class FeatherLeafUploadMixin {
    @Inject(method = "uploadResults(Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;Ljava/util/Collection;)V", at = @At("RETURN"))
    private void cmi$publishLeafMeshes(CommandList commands, Collection<BuilderTaskOutput> results, CallbackInfo ci) {
        if (FeatherLeafMesh.captureEpoch() < 0) return;
        for (var result : results) {
            if (result instanceof ChunkBuildOutput output && !output.render.isDisposed()) {
                FeatherLeafMesh.publish(output.render.getPosition().asLong(), output.render,
                        ((FeatherLeafMesh.Output) output).cmi$getLeafMesh());
            }
        }
    }
}
