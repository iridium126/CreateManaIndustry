package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms;
import org.lwjgl.opengl.*;

/** One candidate hash, one velocity proposal and one swept application. */
public final class DomeBodyContacts {
    private DomeBodyContacts() {}
    public record Programs(int control,int solve,int apply,int audit) {}
    private static final java.util.Map<Integer,Programs> programs=new java.util.HashMap<>();
    public static void register(Programs value){programs.put(value.solve(),value);}
    public static Programs unregister(int solver){return programs.remove(solver);}
    public static int[] related(int solver){var p=programs.get(solver);return p==null?new int[]{solver}:new int[]{p.control(),p.solve(),p.apply(),p.audit()};}
    private static int diagnosticRuns;
    private static void stamp(int[] q,int i){if(q!=null)GL33.glQueryCounter(q[i],GL33.GL_TIMESTAMP);}
    private static void barrier() { GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_COMMAND_BARRIER_BIT); }
    private static void indirect(long offset) { ParticleDiagnostics.INSTANCE.call();GL43.glDispatchComputeIndirect(offset);barrier(); }
    public static void run(int gridProgram,int program,int original,int previous,int pool,int counter,int grid,float time,float dt,int players,StarfallGpuBuffers stars) {
        if(!stars.hasDome()||!stars.domeReady())return;
        int[] timing=null;if(Boolean.getBoolean("cmi.storm.profile")&&++diagnosticRuns==120){timing=new int[7];for(int k=0;k<timing.length;k++)timing[k]=GL15.glGenQueries();}stamp(timing,0);
        var passes=programs.getOrDefault(program,new Programs(program,program,program,program));
        boolean legacy=passes.control()==passes.solve(); // Frozen before-build GPU fixtures only.
        stars.bind(program,true);int state=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,28);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,pool);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,pool);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,3,counter);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,14,counter);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,13,grid);
        GL41.glProgramUniform1i(gridProgram,GpuUniforms.loc(gridProgram,"uPopulationReadEnabled"),0);
        for(int part:related(program)){stars.bindUniforms(part,true);GL41.glProgramUniform1f(part,GpuUniforms.loc(part,"uTimeSec"),time);GL41.glProgramUniform1f(part,GpuUniforms.loc(part,"uDt"),dt);GL41.glProgramUniform1i(part,GpuUniforms.loc(part,"uPlayerCount"),players);}
        GL20.glUseProgram(passes.control());GL41.glProgramUniform1ui(passes.control(),GpuUniforms.loc(passes.control(),"uMode"),4);ParticleDiagnostics.INSTANCE.call();GL43.glDispatchCompute(1,1,1);barrier();stamp(timing,1);
        int oldIndirect=GL11.glGetInteger(GL43.GL_DISPATCH_INDIRECT_BUFFER_BINDING);GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER,state);
        try {
            long full=(StarfallGpuBuffers.DOME_CONTACT_ARGS+4)*4L;
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,grid);GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,0,ParticleBuffers.GRID_TABLE*4L,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(java.nio.IntBuffer)null);barrier();
            GL20.glUseProgram(gridProgram);indirect(full);stamp(timing,2);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,original);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,14,previous);
            if(legacy)GL41.glProgramUniform1ui(program,GpuUniforms.loc(program,"uMode"),0);
            GL20.glUseProgram(passes.solve());indirect(full);stamp(timing,3);
            if(legacy)GL41.glProgramUniform1ui(program,GpuUniforms.loc(program,"uMode"),3);
            GL20.glUseProgram(passes.apply());indirect(full);stamp(timing,4);
            if(legacy)GL41.glProgramUniform1ui(program,GpuUniforms.loc(program,"uMode"),5);
            GL20.glUseProgram(passes.audit());indirect(full);stamp(timing,5);
            // Publish counters from the final masks after all independent audit
            // invocations have finished. Feedback must use one exact snapshot.
            GL20.glUseProgram(passes.control());GL41.glProgramUniform1ui(passes.control(),GpuUniforms.loc(passes.control(),"uMode"),6);GL43.glDispatchCompute(4,1,1);barrier();stamp(timing,6);
            if(timing!=null){long before=GL33.glGetQueryObjectui64(timing[0],GL15.GL_QUERY_RESULT);for(int k=1;k<timing.length;k++){long after=GL33.glGetQueryObjectui64(timing[k],GL15.GL_QUERY_RESULT);System.out.println("Dome body GPU pass "+k+" ms="+(after-before)/1e6);before=after;}for(int query:timing)GL15.glDeleteQueries(query);}
        } finally { GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER,oldIndirect); }
    }
}
