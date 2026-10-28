package com.iridium126.createmanaindustry.client.allaystorm.rift;

import com.iridium126.createmanaindustry.client.gpu.state.GpuTexture2DState;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.*;

/** The vanilla pass and its standalone driver test use the same real ShaderInstance draw path. */
public final class RiftVanillaComposite {
    private static int sampler;
    private RiftVanillaComposite(){}
    public static void draw(ShaderInstance shader){
        int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),function=GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),mask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE);
        try(var state=new SkyRenderState();var textures=new GpuTexture2DState(0,1)){
            state.prepare();RenderSystem.disableCull();RenderSystem.disableBlend();GL11.glDisable(GL11.GL_BLEND);
            RenderSystem.enableDepthTest();RenderSystem.depthFunc(GL11.GL_LEQUAL);RenderSystem.depthMask(false);
            if(GL.getCapabilities().OpenGL33||GL.getCapabilities().GL_ARB_sampler_objects){
                if(sampler==0){sampler=GL33.glGenSamplers();GL33.glSamplerParameteri(sampler,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL33.glSamplerParameteri(sampler,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);GL33.glSamplerParameteri(sampler,GL14.GL_TEXTURE_COMPARE_MODE,GL11.GL_NONE);GL33.glSamplerParameteri(sampler,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);GL33.glSamplerParameteri(sampler,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);}
                GL33.glBindSampler(0,sampler);GL33.glBindSampler(1,sampler);
            }
            var buffer=Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION);
            buffer.addVertex(-1,-1,0);buffer.addVertex(1,-1,0);buffer.addVertex(1,1,0);buffer.addVertex(-1,1,0);
            var mesh=buffer.buildOrThrow();boolean handedOff=false;
            try{shader.apply();handedOff=true;BufferUploader.draw(mesh);}finally{if(!handedOff)mesh.close();shader.clear();}
        }finally{
            // A restored raw VAO cannot remain the uploader's cached immediate buffer.
            BufferUploader.invalidate();GlStateManager._glUseProgram(program);
            RenderSystem.depthMask(mask);RenderSystem.depthFunc(function);
            if(depth)RenderSystem.enableDepthTest();else RenderSystem.disableDepthTest();
            if(cull)RenderSystem.enableCull();else RenderSystem.disableCull();
        }
    }
    public static void close(){if(sampler!=0){GL33.glDeleteSamplers(sampler);sampler=0;}}
}
