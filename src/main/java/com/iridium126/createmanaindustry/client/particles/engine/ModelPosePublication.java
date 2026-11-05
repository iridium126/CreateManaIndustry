package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.allaystorm.*;
import com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants;
import com.iridium126.createmanaindustry.client.gpu.buffer.*;
import com.iridium126.createmanaindustry.client.gpu.frame.GpuFrameCoordinator.Publication;
import com.iridium126.createmanaindustry.client.gpu.shader.*;
import com.iridium126.createmanaindustry.client.gpu.state.*;
import java.util.function.Function;
import org.lwjgl.opengl.*;

/** One GL-owned frozen pose bank. Reads and next-frame writes share the ordered GL stream. */
final class ModelPosePublication implements AutoCloseable {
    private final Function<String,String> sources;
    private int program,buffer,texture,stamp;
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
        }catch(RuntimeException|LinkageError failure){close();throw failure;}
    }
    int publish(ParticleBuffers gpu,Publication source,int displayPool,int emitters,int dispatch,float time,float alpha){
        var animation=gpu.allayAnimation();if(!animation.allocated())return 0;
        allocate();
        if(++stamp==0)stamp++;
        com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(program);gpu.bindPopulationRead(program,false,source.generation());
        gpu.bindVisibility(program);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,0,displayPool);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,3,source.counter());
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,5,emitters);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,GpuResourceConstants.POSE_OUTPUT_BINDING,buffer);
        // The pose program reads the committed animation bank directly. The
        // frozen texture is an output here and is bound only by consumers.
        animation.bindDraw(program,animation.readBank());
        boolean shadow=com.iridium126.createmanaindustry.infrastructure.config.ClientConfig.shaderPackIntegration
                &&com.iridium126.createmanaindustry.CreateManaIndustry.IRISVEIL_ACTIVE
                &&top.leonx.irisveil.IrisVeilCompat.isShaderPackInUse();
        GpuUniforms.setIntUniform(program,"uShadowMayDraw",shadow?1:0);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1f(program,GpuUniforms.loc(program,"uAllayAnimationAlpha"),alpha);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(program,GpuUniforms.loc(program,"poseStamp"),stamp);
        StarfallGpuBuffers.INSTANCE.bind(program,false);StarfallGpuBuffers.INSTANCE.uploadUniforms(program);
        StarfallGpuBuffers.INSTANCE.bindRenderClock(program,time-AllayStormRuntime.INSTANCE.timeSec());
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1f(program,GpuUniforms.loc(program,"uTimeSec"),time);
        GpuBindings.bind(GL43.GL_DISPATCH_INDIRECT_BUFFER,dispatch);
        GpuReplayCommands.memoryBarrier(GL43.GL_COMMAND_BARRIER_BIT);
        ParticleFrameStages.draw("display.pose",()->GpuReplayCommands.dispatchComputeIndirect(12));
        GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
        return texture;
    }
    int stamp(){return texture==0?0:stamp;}
    @Override public void close(){GpuObjectNames.deleteTexture(texture);GpuBufferNames.delete(buffer);GpuPrograms.delete(program);program=buffer=texture=stamp=0;}
}
