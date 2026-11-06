package com.iridium126.createmanaindustry.mixin.iris;

import com.iridium126.createmanaindustry.client.particles.shaderpack.FeatherLeafMesh;
import com.iridium126.createmanaindustry.client.particles.shaderpack.FeatherLeafSway;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = IrisRenderingPipeline.class, remap = false)
abstract class FeatherLeafPipelineMixin {
    @Unique private long cmi$leafEpoch;

    @Inject(method = "<init>(Lnet/irisshaders/iris/shaderpack/programs/ProgramSet;)V", at = @At("TAIL"))
    private void cmi$enableLeafCapture(ProgramSet programs, CallbackInfo ci) {
        boolean capture = FeatherLeafSway.terrain(programs, ProgramId.TerrainCutout) != null
                || FeatherLeafSway.terrain(programs, ProgramId.TerrainSolid) != null;
        cmi$leafEpoch = FeatherLeafMesh.configure(capture);
        // Reuse Iris's existing reload transaction, including for option changes
        // that leave the material map unchanged and would keep old terrain meshes.
        if (capture) ((FeatherLeafTerrainSettings) WorldRenderingSettings.INSTANCE).cmi$requireLeafMeshReload(true);
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void cmi$releaseLeafMeshes(CallbackInfo ci) {
        FeatherLeafMesh.release(cmi$leafEpoch);
    }
}
