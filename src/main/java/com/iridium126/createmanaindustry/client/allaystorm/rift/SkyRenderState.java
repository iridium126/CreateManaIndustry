package com.iridium126.createmanaindustry.client.allaystorm.rift;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.*;

/** Additional state touched by sky replay and Iris framebuffer construction. Render-thread only. */
public final class SkyRenderState implements AutoCloseable {
    private final int draw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),read=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    private final int vao=GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),buffer=GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
    private final int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE),renderbuffer=GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
    private final int srcRgb=GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),dstRgb=GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
    private final int equationRgb=GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB),equationAlpha=GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
    private final int srcAlpha=GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),dstAlpha=GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
    private final boolean scissor=GL11.glIsEnabled(GL11.GL_SCISSOR_TEST),stencil=GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
    private final int buffers=GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS);
    private final boolean independentBlend=GL.getCapabilities().OpenGL40;
    private final java.nio.ByteBuffer color=org.lwjgl.BufferUtils.createByteBuffer(buffers*4);
    private final int[][] blendState=new int[buffers][6];
    private final boolean[] blendEnabled=new boolean[buffers];
    private final int[] viewport=new int[4];
    public SkyRenderState(){
        GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);
        int[] names={GL14.GL_BLEND_SRC_RGB,GL14.GL_BLEND_DST_RGB,GL14.GL_BLEND_SRC_ALPHA,GL14.GL_BLEND_DST_ALPHA,GL20.GL_BLEND_EQUATION_RGB,GL20.GL_BLEND_EQUATION_ALPHA};
        for(int i=0;i<buffers;i++){
            color.position(i*4);GL30.glGetBooleani_v(GL11.GL_COLOR_WRITEMASK,i,color);
            blendEnabled[i]=GL30.glIsEnabledi(GL11.GL_BLEND,i);
            if(independentBlend)for(int j=0;j<names.length;j++)blendState[i][j]=GL30.glGetIntegeri(names[j],i);
        }
        color.position(0);
    }
    public void prepare(){RenderSystem.disableScissor();GL11.glDisable(GL11.GL_SCISSOR_TEST);GL11.glDisable(GL11.GL_STENCIL_TEST);RenderSystem.colorMask(true,true,true,true);GL11.glColorMask(true,true,true,true);}
    @Override public void close(){
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,draw);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,read);
        RenderSystem.viewport(viewport[0],viewport[1],viewport[2],viewport[3]);
        GL30.glBindVertexArray(vao);GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER,buffer);GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER,renderbuffer);
        RenderSystem.activeTexture(active);RenderSystem.blendFuncSeparate(srcRgb,dstRgb,srcAlpha,dstAlpha);
        GL20.glBlendEquationSeparate(equationRgb,equationAlpha);
        if(blendEnabled[0])RenderSystem.enableBlend();else RenderSystem.disableBlend();
        RenderSystem.colorMask(color.get(0)!=0,color.get(1)!=0,color.get(2)!=0,color.get(3)!=0);
        for(int i=0;i<buffers;i++){
            GL30.glColorMaski(i,color.get(i*4)!=0,color.get(i*4+1)!=0,color.get(i*4+2)!=0,color.get(i*4+3)!=0);
            if(blendEnabled[i])GL30.glEnablei(GL11.GL_BLEND,i);else GL30.glDisablei(GL11.GL_BLEND,i);
            if(independentBlend){var b=blendState[i];GL40.glBlendFuncSeparatei(i,b[0],b[1],b[2],b[3]);GL40.glBlendEquationSeparatei(i,b[4],b[5]);}
        }
        if(scissor)GlStateManager._enableScissorTest();else GlStateManager._disableScissorTest();
        if(stencil)GL11.glEnable(GL11.GL_STENCIL_TEST);else GL11.glDisable(GL11.GL_STENCIL_TEST);
    }
}
