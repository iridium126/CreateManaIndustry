package com.iridium126.createmanaindustry.client.render.shaderpack;

import com.iridium126.createmanaindustry.client.allaystorm.rift.AllaySkyProvider;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.minecraft.client.Minecraft;

/** Main-camera suppliers use camera-relative doubles on the CPU, then upload small local float coordinates. */
public final class RiftIrisUniforms {
    private RiftIrisUniforms() {}
    private static float partial(){return Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);}
    public static boolean enabled() {
        var session=ShaderPackSession.current();
        return Iris.getPipelineManager().getPipelineNullable() instanceof RiftIrisPipeline pipeline
                &&pipeline.cmi$riftGeneration()==session.generation()&&session.riftReady();
    }
    public static void bind(UniformHolder uniforms) {
        var frame=UniformUpdateFrequency.PER_FRAME;
        uniforms.uniform4f(frame,"CmiRift",()->AllaySkyProvider.rift(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition(),partial()));
        uniforms.uniform4f(frame,"CmiSkyContext",()->AllaySkyProvider.sky(partial()));
        uniforms.uniform1i(frame,"CmiSkyDimension",()->AllaySkyProvider.dimension()?1:0);
        uniforms.uniform1f(frame,"CmiRiftEnabled",(java.util.function.IntSupplier)()->enabled()?1:0);
        uniforms.uniform1f(frame,"CmiRiftReactive",(java.util.function.IntSupplier)()->AllaySkyProvider.reactive()?1:0);
    }
}
