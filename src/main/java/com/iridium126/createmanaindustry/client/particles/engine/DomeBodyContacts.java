package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms;
import org.lwjgl.opengl.*;

/** One candidate hash; only exceptional bodies enter the bounded GPU queue. */
public final class DomeBodyContacts {
    private DomeBodyContacts() {}
    private static void barrier() { GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_COMMAND_BARRIER_BIT); }
    private static void indirect(long offset) { ParticleDiagnostics.INSTANCE.call();GL43.glDispatchComputeIndirect(offset);barrier(); }
    public static void run(int gridProgram,int program,int original,int previous,int pool,int counter,int grid,float time,float dt,int players,StarfallGpuBuffers stars) {
        if(!stars.hasDome()||!stars.domeReady())return;
        stars.bind(program,true);int state=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,pool);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,pool);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,3,counter);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,14,counter);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,13,grid);
        GL41.glProgramUniform1i(gridProgram,GpuUniforms.loc(gridProgram,"uPopulationReadEnabled"),0);
        GL41.glProgramUniform1f(program,GpuUniforms.loc(program,"uTimeSec"),time);GL41.glProgramUniform1f(program,GpuUniforms.loc(program,"uDt"),dt);GL41.glProgramUniform1i(program,GpuUniforms.loc(program,"uPlayerCount"),players);
        GL20.glUseProgram(program);GL41.glProgramUniform1ui(program,GpuUniforms.loc(program,"uMode"),4);ParticleDiagnostics.INSTANCE.call();GL43.glDispatchCompute(1,1,1);barrier();
        int oldIndirect=GL11.glGetInteger(GL43.GL_DISPATCH_INDIRECT_BUFFER_BINDING);GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER,state);
        try {
            long full=(StarfallGpuBuffers.DOME_CONTACT_ARGS+4)*4L,args=(StarfallGpuBuffers.DOME_CONTACT_ARGS+1)*4L;
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,grid);GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,0,ParticleBuffers.GRID_TABLE*4L,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(java.nio.IntBuffer)null);barrier();
            GL20.glUseProgram(gridProgram);indirect(full);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,original);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,14,previous);
            GL20.glUseProgram(program);GL41.glProgramUniform1ui(program,GpuUniforms.loc(program,"uMode"),0);indirect(full);
            GL41.glProgramUniform1ui(program,GpuUniforms.loc(program,"uMode"),1);ParticleDiagnostics.INSTANCE.call();GL43.glDispatchCompute(1,1,1);barrier();
            for(int iteration=0;iteration<8;iteration++){
                GL41.glProgramUniform1ui(program,GpuUniforms.loc(program,"uIteration"),iteration);
                GL41.glProgramUniform1ui(program,GpuUniforms.loc(program,"uMode"),2);indirect(args);
                GL41.glProgramUniform1ui(program,GpuUniforms.loc(program,"uMode"),3);indirect(args);
            }
            GL41.glProgramUniform1ui(program,GpuUniforms.loc(program,"uMode"),5);indirect(args);
        } finally { GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER,oldIndirect); }
    }
}
