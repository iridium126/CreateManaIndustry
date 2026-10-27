package com.iridium126.createmanaindustry.mixin.iris;

import com.google.common.collect.ImmutableSet;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.CustomTextureManager;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.gl.image.GlImage;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Set;
import java.util.function.Supplier;

/** Iris ABI boundary; source resources returned here are only ever sampled by the target view. */
@Mixin(value=IrisRenderingPipeline.class,remap=false)
public interface NativeSkyPipelineAccess {
    @Accessor("renderTargets") RenderTargets cmi$targets();
    @Accessor("customTextureManager") CustomTextureManager cmi$textures();
    @Accessor("shadowTargetsSupplier") Supplier<ShadowRenderTargets> cmi$shadows();
    @Accessor("customImages") Set<GlImage> cmi$images();
    @Accessor("shaderStorageBufferHolder") ShaderStorageBufferHolder cmi$buffers();
    @Accessor("flippedAfterPrepare") ImmutableSet<Integer> cmi$sourceFlips();
}
