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
    private VanillaRiftRenderer() {}
    public static boolean nativePack() { return CreateManaIndustry.IRIS_ACTIVE && IrisGate.active(); }
    private static final class IrisGate {
        static boolean active() { return net.irisshaders.iris.Iris.getCurrentPack().isPresent()
                &&net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable() instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline; }
    }
    @SubscribeEvent public static void stage(RenderLevelStageEvent event) {
        if (event.getStage()!=RenderLevelStageEvent.Stage.AFTER_SKY || nativePack() || AllaySkyProvider.dimension()) return;
        draw(false, event.getModelViewMatrix(), event.getProjectionMatrix(), event.getCamera(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
    }
    public static boolean dimension(Matrix4f view, Matrix4f projection, Camera camera, float partial) {
        if (nativePack() || !AllaySkyProvider.dimension()) return false;
        return draw(true, view, projection, camera, partial);
    }
    private static boolean draw(boolean dimension, Matrix4f view, Matrix4f projection, Camera camera, float partial) {
        var shader=AllaySkyShaders.shader;
        if (shader==null || Minecraft.getInstance().level==null) return false;
        var rift=AllaySkyProvider.rift(camera.getPosition(), partial);
        if (!dimension && rift.w<0) return false;
        var previous=RenderSystem.getShader();
        boolean depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST), mask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK), cull=GL11.glIsEnabled(GL11.GL_CULL_FACE), blend=GL11.glIsEnabled(GL11.GL_BLEND);
        int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM), depthFunction=GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        try {
            RenderSystem.disableCull(); RenderSystem.disableBlend(); RenderSystem.depthMask(false);
            if (dimension) RenderSystem.disableDepthTest(); else { RenderSystem.enableDepthTest(); RenderSystem.depthFunc(GL11.GL_LEQUAL); }
            RenderSystem.setShader(()->shader);
            shader.safeGetUniform("CmiView").set(view);
            shader.safeGetUniform("CmiProjection").set(projection);
            shader.safeGetUniform("CmiInverseView").set(new Matrix4f(view).invert());
            shader.safeGetUniform("CmiInverseProjection").set(new Matrix4f(projection).invert());
            shader.safeGetUniform("CmiSkyDimension").set(dimension?1:0);
            shader.safeGetUniform("CmiRift").set(rift);
            shader.safeGetUniform("CmiSkyContext").set(AllaySkyProvider.sky(partial));
            BufferBuilder buffer=Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION);
            buffer.addVertex(-1,-1,0); buffer.addVertex(1,-1,0); buffer.addVertex(1,1,0); buffer.addVertex(-1,1,0);
            BufferUploader.drawWithShader(buffer.buildOrThrow());
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
        if (shader==null || nativePack()) return;
        shader.safeGetUniform("CmiInverseCameraView").set(new Matrix4f(cameraView).invert());
        shader.safeGetUniform("CmiRift").set(AllaySkyProvider.rift(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition(),partial));
    }
    public static net.minecraft.client.renderer.ShaderInstance clouds() {
        if (nativePack() || AllaySkyShaders.clouds==null || AllaySkyProvider.rift()==null
                || AllaySkyProvider.rift().state()!=com.iridium126.createmanaindustry.content.allaystorm.rift.StormRiftSnapshot.OPEN) return null;
        return AllaySkyShaders.clouds;
    }
}
