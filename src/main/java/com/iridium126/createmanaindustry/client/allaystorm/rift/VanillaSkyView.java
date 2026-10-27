package com.iridium126.createmanaindustry.client.allaystorm.rift;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.opengl.*;

/** A scoped replay of vanilla sky only: no second world, chunks, clouds or terrain. */
public final class VanillaSkyView {
    private static TextureTarget target;
    private VanillaSkyView() {}
    public static int texture(){return target==null?0:target.getColorTextureId();}
    public static void draw(Matrix4f view,Matrix4f projection,float partial) {
        var mc=Minecraft.getInstance();var rift=AllaySkyProvider.rift();
        if(mc.level==null||rift==null||rift.state()!=com.iridium126.createmanaindustry.content.allaystorm.rift.StormRiftSnapshot.OPEN)return;
        int framebuffer=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),read=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int[] viewport=new int[4];GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);
        boolean depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),mask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),blend=GL11.glIsEnabled(GL11.GL_BLEND),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE);
        var shader=RenderSystem.getShader();
        float[] shaderColor=RenderSystem.getShaderColor().clone();
        int depthFunction=GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        try(var state=new SkyRenderState()) {
            state.prepare();
            int w=mc.getMainRenderTarget().width,h=mc.getMainRenderTarget().height;
            if(target==null)target=new TextureTarget(w,h,true,Minecraft.ON_OSX);
            else if(target.width!=w||target.height!=h)target.resize(w,h,Minecraft.ON_OSX);
            target.setClearColor(0,0,0,1);target.clear(Minecraft.ON_OSX);target.bindWrite(true);
            SkyRenderScope.enter(rift.sky(),Math.max(0,mc.level.getGameTime()-rift.sky().gameTime()));
            mc.levelRenderer.renderSky(view,projection,partial,mc.gameRenderer.getMainCamera(),false,()->{});
        } finally {
            SkyRenderScope.leave();GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,framebuffer);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,read);RenderSystem.viewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            RenderSystem.setShader(()->shader);GL20.glUseProgram(program);RenderSystem.depthMask(mask);
            if(depth)RenderSystem.enableDepthTest();else RenderSystem.disableDepthTest();
            if(cull)RenderSystem.enableCull();else RenderSystem.disableCull();
            RenderSystem.depthFunc(depthFunction);
            RenderSystem.setShaderColor(shaderColor[0],shaderColor[1],shaderColor[2],shaderColor[3]);
        }
    }
    public static void clear(){if(target!=null){target.destroyBuffers();target=null;}}
}
