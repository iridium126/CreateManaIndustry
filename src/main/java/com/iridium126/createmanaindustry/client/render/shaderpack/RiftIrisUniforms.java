package com.iridium126.createmanaindustry.client.render.shaderpack;

import com.iridium126.createmanaindustry.client.allaystorm.rift.AllaySkyProvider;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.DynamicUniformHolder;
import com.iridium126.createmanaindustry.client.allaystorm.rift.RiftRenderFrame;
import net.minecraft.client.Minecraft;

/** Main-camera suppliers use camera-relative doubles on the CPU, then upload small local float coordinates. */
public final class RiftIrisUniforms {
    private RiftIrisUniforms() {}
    private static float partial(){var frame=RiftRenderFrame.current();return frame==null?0:frame.partial();}
    private static net.minecraft.world.phys.Vec3 camera(){var frame=RiftRenderFrame.current();return frame==null?Minecraft.getInstance().gameRenderer.getMainCamera().getPosition():frame.camera();}
    public static boolean enabled() {
        if(RiftRenderFrame.auxiliary()||net.irisshaders.iris.shadows.ShadowRenderingState.areShadowsCurrentlyBeingRendered())return false;
        var session=ShaderPackSession.current();
        return Iris.getPipelineManager().getPipelineNullable() instanceof RiftIrisPipeline pipeline
                &&pipeline.cmi$riftGeneration()==session.generation()&&session.riftReady()&&pipeline.cmi$targetSky()!=null&&pipeline.cmi$targetSky().ready();
    }
    public static void bind(UniformHolder uniforms) {
        // Exclude CustomUniformFixedInputUniformsHolder: its early input graph would overwrite
        // the current matrix/dimension after Program.use(), including during target replay.
        if(!(uniforms instanceof DynamicUniformHolder dynamic))return;
        net.irisshaders.iris.gl.state.ValueUpdateNotifier use=listener->{};
        dynamic.uniformMatrix("CmiSkyViewProjection",com.iridium126.createmanaindustry.client.allaystorm.rift.JuliaProjection::viewProjection,use);
        dynamic.uniformMatrix("CmiTargetInverseViewProjection",()->{var f=RiftRenderFrame.current();return f==null?com.iridium126.createmanaindustry.client.allaystorm.rift.JuliaProjection.viewProjection():f.inverseViewProjection();},use);
        dynamic.uniform4f("CmiRift",()->AllaySkyProvider.rift(camera(),partial()),use);
        dynamic.uniform4f("CmiSkyContext",()->AllaySkyProvider.sky(partial()),use);
        dynamic.uniform1i("CmiSkyDimension",()->com.iridium126.createmanaindustry.client.allaystorm.rift.SkyRenderScope.active()||AllaySkyProvider.dimension()?1:0,use);
        dynamic.uniform1i("CmiTargetPreview",()->com.iridium126.createmanaindustry.client.allaystorm.rift.SkyRenderScope.active()?1:0,use);
        dynamic.uniform4f("CmiStormCenter",()->AllaySkyProvider.stormCenter(camera(),partial()),use);
        dynamic.uniform4f("CmiStormCloud",()->RiftRenderFrame.auxiliary()||net.irisshaders.iris.shadows.ShadowRenderingState.areShadowsCurrentlyBeingRendered()
                ?new org.joml.Vector4f():AllaySkyProvider.stormCloud(camera(),partial()),use);
        dynamic.uniform1f("CmiRiftEnabled",(java.util.function.IntSupplier)()->enabled()
                ||com.iridium126.createmanaindustry.client.allaystorm.rift.SkyRenderScope.active()&&com.iridium126.createmanaindustry.client.allaystorm.rift.JuliaProjection.ready(RiftRenderFrame.current())?1:0,use);
    }
}
