package com.iridium126.createmanaindustry.mixin.iris;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Map;
import net.irisshaders.iris.gl.program.Program;
import net.irisshaders.iris.gl.framebuffer.ViewportData;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

public final class DlssIrisAccess {
    private DlssIrisAccess() {}

    @Mixin(value = IrisRenderingPipeline.class, remap = false)
    public interface Pipeline {
        @Accessor("compositeRenderer") CompositeRenderer cmi$composites();
    }

    @Mixin(value = CompositeRenderer.class, remap = false)
    public interface Composites {
        @Accessor("passes") ImmutableList<?> cmi$passes();
    }

    @Mixin(targets = "net.irisshaders.iris.pipeline.CompositeRenderer$Pass", remap = false)
    public interface Pass {
        @Accessor("name") String cmi$name();
        @Accessor("program") Program cmi$program();
        @Accessor("stageReadsFromAlt") ImmutableSet<Integer> cmi$flipped();
        @Accessor("drawBuffers") int[] cmi$drawBuffers();
        @Accessor("viewportScale") ViewportData cmi$viewport();
    }

    @Mixin(value = RenderTargets.class, remap = false)
    public interface Targets {
        @Accessor("packDirectives") PackDirectives cmi$directives();
        @Accessor("targetSettingsMap") Map<Integer, PackRenderTargetDirectives.RenderTargetSettings> cmi$settings();
    }

}
