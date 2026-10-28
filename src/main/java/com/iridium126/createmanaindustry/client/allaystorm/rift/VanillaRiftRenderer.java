package com.iridium126.createmanaindustry.client.allaystorm.rift;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/** Sky color is replaced before terrain, without hiding terrain on the far side of the plane. */
@EventBusSubscriber(modid=CreateManaIndustry.MODID, value=Dist.CLIENT)
public final class VanillaRiftRenderer {
    private static String status="awaiting vanilla sky stage";
    public static String report(){if(nativePack())return "inactive (native Iris pipeline)";var frame=RiftRenderFrame.current();return status+"; current="+frame+"; mask-ready="+JuliaProjection.ready(frame)+"; target-ready="+VanillaSkyView.ready(frame)+"; sky-clock="+VanillaSkyView.clockReport();}
    private VanillaRiftRenderer() {}
    public static boolean nativePack() { return CreateManaIndustry.IRIS_ACTIVE && IrisGate.active(); }
    private static final class IrisGate {
        static boolean active() { return net.irisshaders.iris.Iris.getCurrentPack().isPresent()
                &&net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable() instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline; }
    }
    @SubscribeEvent public static void stage(RenderLevelStageEvent event) {
        if (event.getStage()!=RenderLevelStageEvent.Stage.AFTER_SKY || nativePack() || AllaySkyProvider.dimension() || RiftRenderFrame.auxiliary()) return;
        var mc=Minecraft.getInstance();var main=mc.getMainRenderTarget();float partial=event.getPartialTick().getGameTimeDeltaPartialTick(false);
        var frame=RiftRenderFrame.begin(0,main.width,main.height,partial,event.getCamera().getPosition(),event.getModelViewMatrix(),event.getProjectionMatrix());
        JuliaProjection.draw(event.getModelViewMatrix(),event.getProjectionMatrix(),event.getPartialTick().getGameTimeDeltaPartialTick(false));
        if(!JuliaProjection.ready(frame)){status="vanilla mask not ready (no open summary or Julia resource)";return;}
        VanillaSkyView.draw(event.getModelViewMatrix(),event.getProjectionMatrix(),event.getPartialTick().getGameTimeDeltaPartialTick(false));
        if(!VanillaSkyView.ready(frame)){status="vanilla target sky not published for current frame";return;}
        status=draw(event.getModelViewMatrix(), event.getProjectionMatrix(), event.getCamera(), partial)?"vanilla composed frame="+frame.number():"vanilla composite shader/summary unavailable";
    }
    private static boolean draw(Matrix4f view, Matrix4f projection, Camera camera, float partial) {
        var shader=AllaySkyShaders.shader;
        if (shader==null || Minecraft.getInstance().level==null) return false;
        var rift=AllaySkyProvider.rift(camera.getPosition(), partial);
        if (rift.w<0) return false;
        var previous=RenderSystem.getShader();
        boolean depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST), mask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK), cull=GL11.glIsEnabled(GL11.GL_CULL_FACE), blend=GL11.glIsEnabled(GL11.GL_BLEND);
        int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM), depthFunction=GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        try {
            RenderSystem.disableCull(); RenderSystem.disableBlend(); RenderSystem.depthMask(false);
            RenderSystem.enableDepthTest(); RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.setShader(()->shader);
            shader.safeGetUniform("CmiView").set(view);
            shader.safeGetUniform("CmiProjection").set(projection);
            shader.safeGetUniform("CmiInverseView").set(new Matrix4f(view).invert());
            shader.safeGetUniform("CmiInverseProjection").set(new Matrix4f(projection).invert());
            shader.safeGetUniform("CmiSkyDimension").set(0);
            shader.safeGetUniform("CmiRift").set(rift);
            shader.safeGetUniform("CmiSkyContext").set(AllaySkyProvider.sky(partial));
            shader.setSampler("CmiJuliaHit",JuliaProjection.texture());
            shader.setSampler("CmiTargetSky",VanillaSkyView.texture());
            shader.safeGetUniform("CmiSkyViewProjection").set(JuliaProjection.viewProjection());
            RiftVanillaComposite.draw(shader);
            return true;
        } finally {
            RenderSystem.setShader(()->previous); GL20.glUseProgram(program);
            RenderSystem.depthMask(mask); RenderSystem.depthFunc(depthFunction);
            if(depth)RenderSystem.enableDepthTest();else RenderSystem.disableDepthTest();
            if(cull)RenderSystem.enableCull();else RenderSystem.disableCull();
            if(blend)RenderSystem.enableBlend();else RenderSystem.disableBlend();
        }
    }
    public static void prepareClouds(Matrix4f cameraView, float partial) {
        var shader=AllaySkyShaders.clouds;
        if (shader==null || nativePack() || AllaySkyProvider.dimension() || SkyRenderScope.active()) return;
        prepareClouds(shader,cameraView,partial,false);
    }
    static void prepareClouds(net.minecraft.client.renderer.ShaderInstance shader,Matrix4f cameraView,float partial,boolean target) {
        shader.safeGetUniform("CmiCloudTarget").set(target?1:0);
        shader.safeGetUniform("CmiInverseCameraView").set(new Matrix4f(cameraView).invert());
        shader.safeGetUniform("CmiRift").set(AllaySkyProvider.rift(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition(),partial));
        shader.setSampler("CmiJuliaHit",JuliaProjection.texture());
        shader.safeGetUniform("CmiSkyViewProjection").set(JuliaProjection.viewProjection());
    }
    public static net.minecraft.client.renderer.ShaderInstance clouds() {
        if (AllaySkyProvider.dimension() || SkyRenderScope.active() || nativePack() || AllaySkyShaders.clouds==null || AllaySkyProvider.rift()==null
                || AllaySkyProvider.rift().state()!=com.iridium126.createmanaindustry.content.allaystorm.rift.StormRiftSnapshot.OPEN) return null;
        return AllaySkyShaders.clouds;
    }
}
