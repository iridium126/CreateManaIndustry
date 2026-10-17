package com.iridium126.createmanaindustry.client.hex.pattern;

import com.iridium126.createmanaindustry.client.gpu.state.GpuComputeState;
import com.iridium126.createmanaindustry.client.packages.render.PackageRenderState;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.*;

/** One boundary per batch, not per pattern. UI clipping/framebuffers are inherited exactly. */
public final class HexPassState implements AutoCloseable {
    private GpuComputeState compute;
    private PackageRenderState draw;
    private boolean[][] indexedColor;
    private final int[] viewport=new int[4],box=new int[4];private final boolean[] color=new boolean[4];private final java.nio.ByteBuffer mask=org.lwjgl.BufferUtils.createByteBuffer(4);
    private ShaderInstance shader;private int drawFbo,readFbo,depthFunc,src,dst,srcA,dstA;private boolean scissor,depth,blend,cull,write,open;
    public HexPassState capture(){
        if(open)throw new IllegalStateException("Nested Hex state boundary");
        // Service/configuration class loading does not require an active GL context.
        if(compute==null){compute=new GpuComputeState(true);draw=new PackageRenderState();indexedColor=new boolean[GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS)][4];}
        shader=RenderSystem.getShader();drawFbo=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);readFbo=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX,box);scissor=GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST);blend=GL11.glIsEnabled(GL11.GL_BLEND);cull=GL11.glIsEnabled(GL11.GL_CULL_FACE);write=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);depthFunc=GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK,mask);for(int i=0;i<4;i++)color[i]=mask.get(i)!=0;
        for(int target=0;target<indexedColor.length;target++){GL30.glGetBooleani_v(GL11.GL_COLOR_WRITEMASK,target,mask);for(int i=0;i<4;i++)indexedColor[target][i]=mask.get(i)!=0;}
        src=GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);dst=GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);srcA=GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);dstA=GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        compute.capture();try{draw.capture();open=true;return this;}catch(RuntimeException error){compute.restore();throw error;}
    }
    public static void nativeState(){RenderSystem.enableDepthTest();RenderSystem.disableCull();RenderSystem.enableBlend();RenderSystem.blendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA);}
    @Override public void close(){if(!open)return;open=false;try{
        RenderSystem.setShader(()->shader);if(depth)RenderSystem.enableDepthTest();else RenderSystem.disableDepthTest();if(blend)RenderSystem.enableBlend();else RenderSystem.disableBlend();if(cull)RenderSystem.enableCull();else RenderSystem.disableCull();RenderSystem.depthMask(write);RenderSystem.depthFunc(depthFunc);RenderSystem.blendFuncSeparate(src,dst,srcA,dstA);RenderSystem.colorMask(color[0],color[1],color[2],color[3]);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,drawFbo);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,readFbo);GL11.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);GL11.glScissor(box[0],box[1],box[2],box[3]);if(scissor)GL11.glEnable(GL11.GL_SCISSOR_TEST);else GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDepthFunc(depthFunc);GL11.glColorMask(color[0],color[1],color[2],color[3]);for(int target=0;target<indexedColor.length;target++){var c=indexedColor[target];GL30.glColorMaski(target,c[0],c[1],c[2],c[3]);}
    }finally{try{draw.restore();}finally{compute.restore();}}}
}
