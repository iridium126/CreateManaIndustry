package com.iridium126.createmanaindustry.mixin.iris;

import com.iridium126.createmanaindustry.accessor.CMIPackageIrisPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.io.IOException;
import java.util.Set;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=IrisRenderingPipeline.class,remap=false)
public abstract class PackageIrisPipelineMixin implements CMIPackageIrisPipeline {
    @Unique private ProgramSet cmi$packageProgramSet;
    @Shadow @Final private Set<ShaderInstance> loadedShaders;
    @Shadow private ShadowRenderTargets shadowRenderTargets;
    @Shadow private boolean destroyed;
    @Inject(method="<init>",at=@At("TAIL"))
    private void cmi$capturePackagePrograms(ProgramSet set,CallbackInfo ci){cmi$packageProgramSet=set;}
    @Override public ProgramSet cmi$packageProgramSet(){return cmi$packageProgramSet;}
    @Override public boolean cmi$packageHasShadows(){return !destroyed && shadowRenderTargets!=null;}
    @Override public void cmi$discardPackageShader(ShaderInstance shader) {
        // Iris owns successful creations. Remove before closing so its later destroy
        // cannot close recycled GL program ids a second time. Destroyed pipelines
        // have already closed every shader, even though they retain the set.
        if(loadedShaders.remove(shader) && !destroyed)shader.close();
    }
    @Override @Invoker("createShader")
    public abstract ShaderInstance cmi$createPackageShader(String name,ProgramSource source,ProgramId id,AlphaTest alpha,
            VertexFormat format,FogMode fog,boolean intensity,boolean fullbright,boolean glint,boolean text,boolean ie) throws IOException;
    @Override @Invoker("createShadowShader")
    public abstract ShaderInstance cmi$createPackageShadowShader(String name,ProgramSource source,ProgramId id,AlphaTest alpha,
            VertexFormat format,boolean intensity,boolean fullbright,boolean text,boolean ie) throws IOException;
}
