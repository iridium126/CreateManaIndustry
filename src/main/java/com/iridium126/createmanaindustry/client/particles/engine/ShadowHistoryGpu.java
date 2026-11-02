package com.iridium126.createmanaindustry.client.particles.engine;

import java.util.function.Function;
import com.iridium126.createmanaindustry.client.gpu.shader.*;
import org.lwjgl.opengl.*;

/** Pre-hand double bank candidate. A certified static-receiver mask is mandatory, never synthesized from depth. */
public final class ShadowHistoryGpu implements AutoCloseable {
    private final int program,sampler;
    private final int[] locations=new int[5],raw=new int[2],reliable=new int[2];
    private final ShadowDepthPyramid[] pyramids=new ShadowDepthPyramid[2];
    private final float[][] matrices={new float[16],new float[16]};
    private final ShadowDepthHistory gate=new ShadowDepthHistory();
    private ShadowDepthHistory.Stamp committed;
    private int width,height,readBank;
    private String reason="no completed history";
    private boolean closed;
    public ShadowHistoryGpu(Function<String,String> sources){
        program=GpuShaderCompiler.compute("CMI history reliability","#version 450 core\n"+sources.apply("shadow_history_validate.comp"));
        String[] names={"uCurrentInverse","uPreviousMatrix","uOriginDelta","uHasHistory","uDepthTolerance"};for(int i=0;i<names.length;i++)locations[i]=GL20.glGetUniformLocation(program,names[i]);
        sampler=GL33.glGenSamplers();GL33.glSamplerParameteri(sampler,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL33.glSamplerParameteri(sampler,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);GL33.glSamplerParameteri(sampler,GL14.GL_TEXTURE_COMPARE_MODE,GL11.GL_NONE);
        try{for(int i=0;i<2;i++)pyramids[i]=new ShadowDepthPyramid(sources);}catch(RuntimeException error){close();throw error;}
    }
    public String reason(){return reason;}
    public boolean capture(ShadowDepthHistory.Stamp stamp,int depth,int certifiedMask,float[] matrix,float[] inverse,float tolerance){
        if(closed)throw new IllegalStateException("Depth history closed");
        if(certifiedMask<=0){invalidate("static-receiver reliability attachment missing");return false;}
        if(depth<=0||stamp.width()<1||stamp.height()<1||matrix.length!=16||inverse.length!=16||!Float.isFinite(tolerance)||tolerance<0)throw new IllegalArgumentException("History capture input");
        for(float value:matrix)if(!Float.isFinite(value)){invalidate("nonfinite history matrix");return false;}
        for(float value:inverse)if(!Float.isFinite(value)){invalidate("nonfinite history inverse");return false;}
        boolean compatible=gate.accepts(stamp);
        if(width!=stamp.width()||height!=stamp.height()){
            releaseTextures();width=stamp.width();height=stamp.height();compatible=false;
            for(int i=0;i<2;i++){raw[i]=GL45.glCreateTextures(GL11.GL_TEXTURE_2D);GL45.glTextureStorage2D(raw[i],1,GL30.GL_RG32F,width,height);reliable[i]=GL45.glCreateTextures(GL11.GL_TEXTURE_2D);GL45.glTextureStorage2D(reliable[i],1,GL30.GL_R32F,width,height);}
        }
        int write=1-readBank;
        try{
            GL20.glUseProgram(program);GL45.glBindTextureUnit(4,depth);GL45.glBindTextureUnit(5,raw[readBank]);GL45.glBindTextureUnit(6,certifiedMask);
            for(int unit=4;unit<=6;unit++)GL33.glBindSampler(unit,sampler);
            GL42.glBindImageTexture(0,raw[write],0,false,0,GL15.GL_WRITE_ONLY,GL30.GL_RG32F);GL42.glBindImageTexture(1,reliable[write],0,false,0,GL15.GL_WRITE_ONLY,GL30.GL_R32F);
            GL41.glProgramUniformMatrix4fv(program,locations[0],false,inverse);GL41.glProgramUniformMatrix4fv(program,locations[1],false,matrices[readBank]);
            GL41.glProgramUniform3f(program,locations[2],compatible?(float)(stamp.x()-committed.x()):0,compatible?(float)(stamp.y()-committed.y()):0,compatible?(float)(stamp.z()-committed.z()):0);
            GL41.glProgramUniform1i(program,locations[3],compatible?1:0);GL41.glProgramUniform1f(program,locations[4],tolerance);
            GL42.glMemoryBarrier(GL42.GL_FRAMEBUFFER_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
            GL43.glDispatchCompute((width+7)/8,(height+7)/8,1);GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
            pyramids[write].build(reliable[write],width,height,false);
            System.arraycopy(matrix,0,matrices[write],0,16);gate.publish(stamp);committed=stamp;readBank=write;
            reason=compatible?"GPU reliability filtered; previous-frame temporal error permitted":"history bootstrap; no rejection evidence";return true;
        }catch(RuntimeException error){invalidate("history candidate submission failed");throw error;}
    }
    public ShadowDepthContext context(ShadowDepthHistory.Stamp now,float margin){
        if(committed==null||!gate.accepts(now)){reason=gate.reason();return null;}
        var p=pyramids[readBank];return new ShadowDepthContext(p.texture(),p.sampler(),width,height,matrices[readBank],0,0,1,margin,0,false,
                (float)(now.x()-committed.x()),(float)(now.y()-committed.y()),(float)(now.z()-committed.z()));
    }
    public void invalidate(String why){gate.invalidate(why);committed=null;reason=why;}
    private void releaseTextures(){for(int i=0;i<2;i++){if(raw[i]!=0)GL11.glDeleteTextures(raw[i]);if(reliable[i]!=0)GL11.glDeleteTextures(reliable[i]);raw[i]=reliable[i]=0;}width=height=0;invalidate("history dimensions changed");}
    @Override public void close(){if(closed)return;closed=true;releaseTextures();for(int i=0;i<2;i++)if(pyramids[i]!=null){pyramids[i].close();pyramids[i]=null;}GL33.glDeleteSamplers(sampler);GpuPrograms.delete(program);}
}
