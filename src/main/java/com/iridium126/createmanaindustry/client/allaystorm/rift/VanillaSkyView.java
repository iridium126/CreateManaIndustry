package com.iridium126.createmanaindustry.client.allaystorm.rift;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.opengl.*;

/** A scoped replay of vanilla sky only: no second world, chunks or terrain; independent native cloud cache. */
public final class VanillaSkyView {
    private static TextureTarget target;
    private static RiftRenderFrame published;
    private static long sourceClock,replayClock,expectedClock;
    private static boolean clockObserved;
    public static String clockReport(){return !clockObserved?"awaiting vanilla replay":
            "source="+sourceClock+" target="+replayClock+" expected="+expectedClock+(replayClock==expectedClock?" (scoped)":" (clock mismatch)");}
    public static boolean ready(RiftRenderFrame frame){return frame!=null&&published==frame&&target!=null&&frame.matches(0,target.width,target.height);}
    private static final TargetVanillaClouds clouds=new TargetVanillaClouds();
    private VanillaSkyView() {}
    public static String cloudReport(){return clouds.report();}
    public static int texture(){return target==null?0:target.getColorTextureId();}
    public static void draw(Matrix4f view,Matrix4f projection,float partial) {
        published=null;
        clockObserved=false;
        var mc=Minecraft.getInstance();var rift=AllaySkyProvider.rift();
        if(mc.level==null||rift==null||rift.state()!=com.iridium126.createmanaindustry.content.allaystorm.rift.StormRiftSnapshot.OPEN)return;
        int framebuffer=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),read=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int[] viewport=new int[4];GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);
        boolean depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),mask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),blend=GL11.glIsEnabled(GL11.GL_BLEND),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE);
        var shader=RenderSystem.getShader();
        float[] shaderColor=RenderSystem.getShaderColor().clone();
        int depthFunction=GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        try(var state=new SkyRenderState();var textures=new com.iridium126.createmanaindustry.client.gpu.state.GpuTexture2DState(0,1,2)) {
            state.prepare();
            int w=mc.getMainRenderTarget().width,h=mc.getMainRenderTarget().height;
            if(target==null)target=new TextureTarget(w,h,true,Minecraft.ON_OSX);
            else if(target.width!=w||target.height!=h)target.resize(w,h,Minecraft.ON_OSX);
            target.setClearColor(0,0,0,1);target.clear(Minecraft.ON_OSX);target.bindWrite(true);
            sourceClock=mc.level.dayTime();
            SkyRenderScope.enter(rift.sky(),Math.max(0,mc.level.getGameTime()-rift.sky().gameTime()));
            replayClock=mc.level.dayTime();expectedClock=SkyRenderScope.dayTime();clockObserved=true;
            if(replayClock!=expectedClock)return; // never publish the unchanged source sky as a valid target
            RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionShader);
            mc.levelRenderer.renderSky(view,projection,partial,mc.gameRenderer.getMainCamera(),false,()->{});
            clouds.draw(view,projection,partial,target);
            published=RiftRenderFrame.current();
        } finally {
            SkyRenderScope.leave();GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,framebuffer);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,read);RenderSystem.viewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            RenderSystem.setShader(()->shader);GL20.glUseProgram(program);RenderSystem.depthMask(mask);
            if(depth)RenderSystem.enableDepthTest();else RenderSystem.disableDepthTest();
            if(cull)RenderSystem.enableCull();else RenderSystem.disableCull();
            RenderSystem.depthFunc(depthFunction);
            RenderSystem.setShaderColor(shaderColor[0],shaderColor[1],shaderColor[2],shaderColor[3]);
        }
    }
    public static void clear(){published=null;clockObserved=false;clouds.close();RiftVanillaComposite.close();if(target!=null){target.destroyBuffers();target=null;}}
}
