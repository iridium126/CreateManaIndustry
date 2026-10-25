package com.iridium126.createmanaindustry.client.render.shaderpack;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.gpu.profile.GpuTimestampRing;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.shader.program.*;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.*;

/** Shared native/display-space compositor. The sampled scene is always an owned copy, never the draw attachment. */
public final class WorldEffectRenderer {
    private static final SceneColorCopy sceneCopy=new SceneColorCopy();
    private static int colorSampler,rawSampler;
    private static String failure="";
    private static final java.util.Map<String,GpuTimestampRing> timers=new java.util.LinkedHashMap<>();
    private WorldEffectRenderer(){}
    public static boolean shaderReady(String name){
        ShaderProgram shader=VeilRenderSystem.renderer().getShaderManager().getShader(CreateManaIndustry.modLoc(name));
        return shader!=null&&shader.isValid();
    }
    public static boolean draw(WorldEffectPass pass,PackCapabilities.WorldPath path) {
        failure="";
        ShaderProgram shader=VeilRenderSystem.renderer().getShaderManager().getShader(CreateManaIndustry.modLoc(pass.shader()));
        var main=Minecraft.getInstance().getMainRenderTarget();
        if(shader==null||!shader.isValid())return unavailable("effect shader unavailable");
        if(!GL11.glIsTexture(main.getDepthTextureId()))return unavailable("main depth texture unavailable");
        boolean timed=false,completed=false;
        var timer=timers.computeIfAbsent(pass.id(),id->new GpuTimestampRing(4));
        try(var state=new WorldEffectState()) {
            int target=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            if(target==0)return unavailable("draw target is the default framebuffer");
            if(GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)!=GL11.GL_TEXTURE)return unavailable("draw color attachment is not a texture");
            int attachment=GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            if(!GL11.glIsTexture(attachment))return unavailable("draw color texture unavailable");
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,attachment);
            int w=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH),h=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_HEIGHT);
            if(w!=main.width||h!=main.height)return unavailable("native target size "+w+"x"+h+" differs from main "+main.width+"x"+main.height);
            timed=timer.begin();
            int exposure=-1;
            if(path==PackCapabilities.WorldPath.TRANSLUCENT){exposure=com.iridium126.createmanaindustry.client.render.mist.MistExposureSource.acquireExposureTexture(ShaderPackSession.current().capabilities().exposureBuffer());GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,target);if(!GL11.glIsTexture(exposure))return unavailable("native exposure texture unavailable");}
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);int copy=sceneCopy.capture(w,h);
            RenderSystem.viewport(0,0,w,h);RenderSystem.disableBlend();RenderSystem.disableDepthTest();RenderSystem.depthMask(false);RenderSystem.disableCull();RenderSystem.colorMask(true,true,true,true);GL11.glDisable(GL11.GL_SCISSOR_TEST);
            // Veil's bind/unbind clears global blocks and sampler units. This pass owns only its local bindings.
            GL20.glUseProgram(shader.getProgram());
            if(!com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.applyWorldEffectCamera(shader))return unavailable("main camera snapshot unavailable");
            bind(shader,"DiffuseSampler0",0,copy);bind(shader,"DiffuseDepthSampler",1,main.getDepthTextureId());
            setInt(shader,"ExposureMode",path==PackCapabilities.WorldPath.HDR?2:path==PackCapabilities.WorldPath.TRANSLUCENT?1:0);
            setInt(shader,"ExposureBound",exposure>0?1:0);bind(shader,"ExposureSampler",3,Math.max(0,exposure));
            var ep=shader.getUniform("ExposureParams");if(ep!=null)ep.setVector(path==PackCapabilities.WorldPath.HDR?SundialAutoExposure.compensationScale():1,0,0,0);
            setInt(shader,"MistTargetMode",path==PackCapabilities.WorldPath.RAW?3:path==PackCapabilities.WorldPath.TRANSLUCENT?1:0);
            setInt(shader,"OutputMode",path==PackCapabilities.WorldPath.RAW?2:path==PackCapabilities.WorldPath.TRANSLUCENT?1:0);
            var sceneGamma=shader.getUniform("CmiSceneGamma");if(sceneGamma!=null)sceneGamma.setFloat(path==PackCapabilities.WorldPath.SCENE?ShaderPackSession.current().nativeGamma():1);
            boolean shadow=CreateManaIndustry.IRIS_ACTIVE&&ShadowDistortionRegistry.recognized()&&GL11.glIsTexture(IrisShadowTextures.getShadowDepthTextureId());
            setInt(shader,"ShadowMapBound",shadow?1:0);setInt(shader,"ShadowMap1Bound",0);setInt(shader,"ColoredShadows",0);
            if(shadow){
                int id=IrisShadowTextures.getShadowDepthTextureId();bind(shader,"ShadowMap0",2,id);
                var resolution=shader.getUniform("ShadowMapResolution");if(resolution!=null)resolution.setFloat(GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH));
                var p=ShadowDistortionRegistry.resolveForCurrentPack();setInt(shader,"ShadowDistortionMode",p.glslMode());
                var bias=shader.getUniform("ShadowDistortion");if(bias!=null)bias.setFloat(p.bias());var scale=shader.getUniform("ShadowDepthScale");if(scale!=null)scale.setFloat(p.depthScale());
                var log=shader.getUniform("ShadowLogParams");if(log!=null)log.setVector(p.logK(),p.logA(),p.logB(),p.depthScale());
                var sm=shader.getUniform("ShadowModelView");if(sm!=null)sm.setMatrix(net.irisshaders.iris.shadows.ShadowRenderer.MODELVIEW);
                var sp=shader.getUniform("ShadowProjection");if(sp!=null)sp.setMatrix(net.irisshaders.iris.shadows.ShadowRenderer.PROJECTION);
                if(ShaderPackSession.current().capabilities().worldPath()==PackCapabilities.WorldPath.TRANSLUCENT
                        &&ActivePackOptions.isEnabled("TRANSLUCENT_COLORED_SHADOWS")&&GL11.glIsTexture(IrisShadowTextures.getOpaqueDepthTextureId())
                        &&IrisShadowTextures.getShadowTargets() instanceof net.irisshaders.iris.shadows.ShadowRenderTargets targets){
                    var color=targets.getOrCreate(0);int colorId=targets.isFlipped(0)?color.getAltTexture():color.getMainTexture();
                    if(GL11.glIsTexture(colorId)){bind(shader,"ShadowMap1",4,IrisShadowTextures.getOpaqueDepthTextureId());bind(shader,"ShadowColor0",5,colorId);setInt(shader,"ShadowMap1Bound",1);setInt(shader,"ColoredShadows",1);}
                }
            }
            pass.uniforms().accept(shader);VeilRenderSystem.drawScreenQuad();
            completed=true;return true;
        } finally {if(timed)timer.end(completed);}
    }
    private static boolean unavailable(String reason){failure=reason;return false;}
    public static String failure(){return failure;}
    private static void setInt(UniformAccess shader,String name,int value){var u=shader.getUniform(name);if(u!=null)u.setInt(value);}
    private static int sampler(boolean color){int id=color?colorSampler:rawSampler;if(id==0){id=GL33.glGenSamplers();GL33.glSamplerParameteri(id,GL11.GL_TEXTURE_MIN_FILTER,color?GL11.GL_LINEAR:GL11.GL_NEAREST);GL33.glSamplerParameteri(id,GL11.GL_TEXTURE_MAG_FILTER,color?GL11.GL_LINEAR:GL11.GL_NEAREST);GL33.glSamplerParameteri(id,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);GL33.glSamplerParameteri(id,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);GL33.glSamplerParameteri(id,GL14.GL_TEXTURE_COMPARE_MODE,GL11.GL_NONE);if(color)colorSampler=id;else rawSampler=id;}return id;}
    private static void bind(ShaderProgram shader,String name,int unit,int texture){RenderSystem.activeTexture(GL13.GL_TEXTURE0+unit);GL33.glBindSampler(unit,sampler(unit==0));GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);int loc=shader.getUniformLocation(name);if(loc>=0)GL20.glUniform1i(loc,unit);}
    public static double gpuMs(String id){var timer=timers.get(id);return timer==null?0:timer.lastMs();}
    public static void close(){sceneCopy.close();if(colorSampler!=0)GL33.glDeleteSamplers(colorSampler);if(rawSampler!=0)GL33.glDeleteSamplers(rawSampler);colorSampler=rawSampler=0;timers.values().forEach(GpuTimestampRing::close);timers.clear();}
}
