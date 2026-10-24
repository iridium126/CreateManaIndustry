package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms;
import org.lwjgl.opengl.*;

public final class DomeGatherNavigation {
    private DomeGatherNavigation() {}
    public static void run(int program,float time,StarfallGpuBuffers stars) {
        run(program,time,stars,GL11.glGetInteger(GL43.GL_DISPATCH_INDIRECT_BUFFER_BINDING));
    }
    public static void run(int program,float time,StarfallGpuBuffers stars,int previousIndirect) {
        if(!stars.navigationReady())return;
        stars.bind(program,true);
        stars.bindPathDispatch();
        GL41.glProgramUniform1f(program,GpuUniforms.loc(program,"uTimeSec"),time);
        dispatch(program,stars.domePathDispatchBuffer(),previousIndirect);
    }
    public static void dispatch(int program,int state,int previousIndirect) {
        GL20.glUseProgram(program);
        GL41.glProgramUniform1ui(program,GpuUniforms.loc(program,"uMode"),0);
        ParticleDiagnostics.INSTANCE.call();GL43.glDispatchCompute(1,1,1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL43.GL_COMMAND_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER,state);
        try {
            GL41.glProgramUniform1ui(program,GpuUniforms.loc(program,"uMode"),1);
            ParticleDiagnostics.INSTANCE.call();GL43.glDispatchComputeIndirect(0);
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
        } finally {GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER,previousIndirect);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,29,previousIndirect);}
    }
}
