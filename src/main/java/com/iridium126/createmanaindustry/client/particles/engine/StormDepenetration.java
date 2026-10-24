package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers;
import com.iridium126.createmanaindustry.client.allaystorm.StormCollisionRuntime;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms;
import org.lwjgl.opengl.*;

/** Sparse corrections belong exclusively to the uncommitted output pool/bank. */
public final class StormDepenetration {
    private StormDepenetration() {}
    public static void run(int prepare,int schedule,int search,float time,StarfallGpuBuffers stars){
        if(!stars.navigationReady())return;
        stars.bind(search,true);
        for(int program:new int[]{prepare,schedule,search}){
            if(program!=search)stars.bindFrame(program,true);
            GL41.glProgramUniform1f(program,GpuUniforms.loc(program,"uTimeSec"),time);
        }
        StormCollisionRuntime.bindCurrent(search);
        dispatch(prepare,schedule,search,stars);
    }
    /** Standalone real-driver fixtures supply the same immutable collision publication. */
    public static void dispatch(int prepare,int schedule,int search,StarfallGpuBuffers stars){
        int previousStorage=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,29);
        stars.bindPathDispatch();
        GL20.glUseProgram(prepare);ParticleDiagnostics.INSTANCE.call();GL43.glDispatchCompute(1,1,1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL43.GL_COMMAND_BARRIER_BIT);
        int previous=GL11.glGetInteger(GL43.GL_DISPATCH_INDIRECT_BUFFER_BINDING);
        GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER,stars.domePathDispatchBuffer());
        try{GL20.glUseProgram(schedule);ParticleDiagnostics.INSTANCE.call();GL43.glDispatchComputeIndirect(0);}
        finally{GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER,previous);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,29,previousStorage);}
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        GL20.glUseProgram(search);ParticleDiagnostics.INSTANCE.call();GL43.glDispatchCompute(16,1,1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }
}
