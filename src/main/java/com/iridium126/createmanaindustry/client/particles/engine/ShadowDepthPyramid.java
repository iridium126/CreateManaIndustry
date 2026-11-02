package com.iridium126.createmanaindustry.client.particles.engine;

import java.util.function.Function;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms;

/** Owns a max/min depth hierarchy. Source texture state is never modified. Caller owns GL state. */
public final class ShadowDepthPyramid implements AutoCloseable {
    private final int program,sourceLevel,reverse,sampler;
    private int texture,width,height,levels;
    private boolean closed;
    public ShadowDepthPyramid(Function<String,String> sources){
        program=GpuShaderCompiler.compute("CMI native shadow hierarchy","#version 450 core\n"+sources.apply("shadow_depth_reduce.comp"));
        sourceLevel=GL20.glGetUniformLocation(program,"uSourceLevel");reverse=GL20.glGetUniformLocation(program,"uReverseDepth");
        sampler=GL33.glGenSamplers();GL33.glSamplerParameteri(sampler,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
        GL33.glSamplerParameteri(sampler,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
        GL33.glSamplerParameteri(sampler,GL14.GL_TEXTURE_COMPARE_MODE,GL11.GL_NONE);
        GL33.glSamplerParameteri(sampler,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
        GL33.glSamplerParameteri(sampler,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
    }
    public int texture(){return texture;}public int levels(){return levels;}
    public int sampler(){return sampler;}
    public long bytes(){long bytes=0;for(int l=0;l<levels;l++)bytes+=4L*Math.max(1,width>>l)*Math.max(1,height>>l);return bytes;}
    public void build(int source,int sourceWidth,int sourceHeight,boolean reversed){
        if(closed)throw new IllegalStateException("Depth hierarchy closed");
        if(source<=0||sourceWidth<1||sourceHeight<1)throw new IllegalArgumentException("Native depth source");
        int w=Math.max(1,(sourceWidth+1)/2),h=Math.max(1,(sourceHeight+1)/2);
        if(texture==0||width!=w||height!=h){releaseTexture();width=w;height=h;levels=32-Integer.numberOfLeadingZeros(Math.max(w,h));
            texture=GL45.glCreateTextures(GL11.GL_TEXTURE_2D);GL45.glTextureStorage2D(texture,levels,GL30.GL_R32F,w,h);}
        GL20.glUseProgram(program);GL41.glProgramUniform1i(program,reverse,reversed?1:0);
        // First reduction reads the live native depth directly; there is no full-resolution copy.
        GL42.glMemoryBarrier(GL42.GL_FRAMEBUFFER_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
        GL33.glBindSampler(4,sampler);
        for(int l=0;l<levels;l++){
            GL45.glBindTextureUnit(4,l==0?source:texture);GL41.glProgramUniform1i(program,sourceLevel,l==0?0:l-1);
            GL42.glBindImageTexture(0,texture,l,false,0,GL15.GL_WRITE_ONLY,GL30.GL_R32F);
            GL43.glDispatchCompute((Math.max(1,w>>l)+7)/8,(Math.max(1,h>>l)+7)/8,1);
            GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
        }
    }
    private void releaseTexture(){if(texture!=0)GL11.glDeleteTextures(texture);texture=0;levels=0;}
    @Override public void close(){if(closed)return;closed=true;releaseTexture();GL33.glDeleteSamplers(sampler);GpuPrograms.delete(program);}
}
