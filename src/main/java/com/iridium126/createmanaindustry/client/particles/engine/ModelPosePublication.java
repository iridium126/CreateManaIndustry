package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.allaystorm.*;
import com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants;
import com.iridium126.createmanaindustry.client.gpu.buffer.*;
import com.iridium126.createmanaindustry.client.gpu.shader.*;
import com.iridium126.createmanaindustry.client.gpu.state.*;
import java.util.function.Function;
import org.lwjgl.opengl.*;

/** One GL-owned frozen pose bank. Reads and next-frame writes share the ordered GL stream. */
final class ModelPosePublication implements AutoCloseable {
    private final Function<String,String> sources;
    private int program,buffer,texture,dispatch,stamp;
    ModelPosePublication(Function<String,String> sources){this.sources=sources;}
    private void allocate(){
        if(buffer!=0)return;
        long rows=GpuResourceConstants.POSE_ANIMATION_ROWS+(long)AllayAnimationGpu.CAPACITY*GpuResourceConstants.POSE_ROWS_PER_INSTANCE;
        if(rows>GL11.glGetInteger(GL31.GL_MAX_TEXTURE_BUFFER_SIZE)||rows*16>GL32.glGetInteger64(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE))
            throw new IllegalStateException("Frozen model pose capacity exceeds device limits");
        try{
            program=GpuShaderCompiler.compute("frozen model poses","#version 450 core\n"+ParticlePrograms.commonPrelude()+sources.apply("pose_cache.comp"));
            GpuProgramContract.installSamplers(program);
            GpuProgramContract.verify(program,"pose_cache.comp");
            buffer=GL45.glCreateBuffers();GL45.glNamedBufferData(buffer,rows*16,GL15.GL_DYNAMIC_COPY);
            if(GL45.glGetNamedBufferParameteri64(buffer,GL15.GL_BUFFER_SIZE)!=rows*16)throw new IllegalStateException("Frozen pose allocation failed");
            texture=GL45.glCreateTextures(GL31.GL_TEXTURE_BUFFER);GL45.glTextureBuffer(texture,GL30.GL_RGBA32UI,buffer);
            dispatch=GL45.glCreateBuffers();
            GL45.glNamedBufferData(dispatch,20,GL15.GL_DYNAMIC_COPY);
            clear();
        }catch(RuntimeException|LinkageError failure){close();throw failure;}
    }
    private void clear(){
        GpuReplayCommands.clearNamedBufferData(buffer,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(java.nio.IntBuffer)null);
        GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }
    int begin(ParticleBuffers gpu){
        if(!gpu.allayAnimation().allocated())return 0;
        allocate();
        if(++stamp==0){clear();stamp=1;}
        return texture;
    }
    void publish(ParticleBuffers gpu,int displayPool,int counter,int emitters,int commands,int instances,float time,float alpha,String stage){
        if(texture==0||!gpu.allayAnimation().allocated())return;
        var animation=gpu.allayAnimation();
        GpuBindings.program(program);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,0,displayPool);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,1,commands);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,2,instances);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,3,counter);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,4,dispatch);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,5,emitters);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,GpuResourceConstants.POSE_OUTPUT_BINDING,buffer);
        // The pose program reads the committed animation bank directly. The
        // frozen texture is an output here and is bound only by consumers.
        animation.bindDraw(program,animation.readBank());
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1f(program,GpuUniforms.loc(program,"uAllayAnimationAlpha"),alpha);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(program,GpuUniforms.loc(program,"poseStamp"),stamp);
        StarfallGpuBuffers.INSTANCE.bind(program,false);StarfallGpuBuffers.INSTANCE.uploadUniforms(program);
        StarfallGpuBuffers.INSTANCE.bindRenderClock(program,time-AllayStormRuntime.INSTANCE.timeSec());
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1f(program,GpuUniforms.loc(program,"uTimeSec"),time);
        var capture=ClientFrameCapture.INSTANCE;
        boolean measure=capture.collecting();
        GpuUniforms.setIntUniform(program,"uMeasurePose",measure?1:0);
        GpuBindings.bind(GL43.GL_DISPATCH_INDIRECT_BUFFER,dispatch);
        ParticleFrameStages.draw(stage,()->{
            GpuUniforms.setIntUniform(program,"uPreparePoseDispatch",1);
            GpuReplayCommands.dispatchCompute(1,1,1);
            GpuReplayCommands.memoryBarrier(GL43.GL_COMMAND_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            GpuUniforms.setIntUniform(program,"uPreparePoseDispatch",0);
            GpuReplayCommands.dispatchComputeIndirect(0);
        });
        GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
        if(measure)capture.poseSubmitted(dispatch,stage.endsWith(".shadow"));
    }
    int stamp(){return texture==0?0:stamp;}
    @Override public void close(){GpuObjectNames.deleteTexture(texture);GpuBufferNames.delete(buffer);GpuBufferNames.delete(dispatch);GpuPrograms.delete(program);program=buffer=texture=dispatch=stamp=0;}
}
