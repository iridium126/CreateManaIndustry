package com.iridium126.createmanaindustry.client.render.shaderpack;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.*;

/** Foreign pipeline boundary. Restores every GL state modified by a full-screen effect or scene copy. */
public final class WorldEffectState implements AutoCloseable {
    private final int draw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),read=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    private final int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),vao=GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    private final int[] viewport=new int[4],textures=new int[6],samplers=new int[6],colorMask=new int[4];
    private final int src=GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),dst=GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),srcA=GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),dstA=GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
    private final int eq=GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB),eqA=GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
    private final boolean blend=GL11.glIsEnabled(GL11.GL_BLEND),depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE),scissor=GL11.glIsEnabled(GL11.GL_SCISSOR_TEST),depthMask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
    private final int drawBuffers=GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS);
    private final boolean[] indexedBlend=new boolean[drawBuffers];
    private final int[][] indexedState=new int[drawBuffers][10];
    public WorldEffectState(){
        GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);
        GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK,colorMask);
        // Non-indexed blend/color calls change every attachment, including MRTs this pass does not draw to.
        for(int i=1;i<drawBuffers;i++){
            indexedBlend[i]=GL30.glIsEnabledi(GL11.GL_BLEND,i);
            int[] values=indexedState[i];
            values[0]=GL30.glGetIntegeri(GL14.GL_BLEND_SRC_RGB,i);values[1]=GL30.glGetIntegeri(GL14.GL_BLEND_DST_RGB,i);
            values[2]=GL30.glGetIntegeri(GL14.GL_BLEND_SRC_ALPHA,i);values[3]=GL30.glGetIntegeri(GL14.GL_BLEND_DST_ALPHA,i);
            values[4]=GL30.glGetIntegeri(GL20.GL_BLEND_EQUATION_RGB,i);values[5]=GL30.glGetIntegeri(GL20.GL_BLEND_EQUATION_ALPHA,i);
            int[] mask=new int[4];GL30.glGetIntegeri_v(GL11.GL_COLOR_WRITEMASK,i,mask);System.arraycopy(mask,0,values,6,4);
        }
        for(int i=0;i<6;i++){RenderSystem.activeTexture(GL13.GL_TEXTURE0+i);textures[i]=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);samplers[i]=GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,i);}
        RenderSystem.activeTexture(active);
    }
    public void close(){
        for(int i=0;i<6;i++){RenderSystem.activeTexture(GL13.GL_TEXTURE0+i);GL11.glBindTexture(GL11.GL_TEXTURE_2D,textures[i]);GL33.glBindSampler(i,samplers[i]);}
        RenderSystem.activeTexture(active);GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,draw);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,read);
        GL20.glUseProgram(program);GL30.glBindVertexArray(vao);RenderSystem.viewport(viewport[0],viewport[1],viewport[2],viewport[3]);
        if(blend)RenderSystem.enableBlend();else RenderSystem.disableBlend();
        RenderSystem.blendFuncSeparate(src,dst,srcA,dstA);GL20.glBlendEquationSeparate(eq,eqA);
        if(depth)RenderSystem.enableDepthTest();else RenderSystem.disableDepthTest();
        if(cull)RenderSystem.enableCull();else RenderSystem.disableCull();
        if(scissor)GL11.glEnable(GL11.GL_SCISSOR_TEST);else GL11.glDisable(GL11.GL_SCISSOR_TEST);
        RenderSystem.depthMask(depthMask);
        RenderSystem.colorMask(colorMask[0]!=0,colorMask[1]!=0,colorMask[2]!=0,colorMask[3]!=0);
        for(int i=1;i<drawBuffers;i++){
            int[] values=indexedState[i];
            if(indexedBlend[i])GL30.glEnablei(GL11.GL_BLEND,i);else GL30.glDisablei(GL11.GL_BLEND,i);
            GL40.glBlendFuncSeparatei(i,values[0],values[1],values[2],values[3]);
            GL40.glBlendEquationSeparatei(i,values[4],values[5]);
            GL30.glColorMaski(i,values[6]!=0,values[7]!=0,values[8]!=0,values[9]!=0);
        }
    }
}
