package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.gpu.state.GpuBindings;

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
    private static void barrier() { com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_COMMAND_BARRIER_BIT); }
    private static void indirect(long offset) { ParticleDiagnostics.INSTANCE.call();com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchComputeIndirect(offset);barrier(); }
    public static void run(int gridProgram,int program,int original,int previous,int pool,int counter,int grid,float time,float dt,int players,StarfallGpuBuffers stars) {
        run(gridProgram,program,original,previous,pool,counter,grid,time,dt,players,stars,0,-1);
    }
    static void run(int gridProgram,int program,int original,int previous,int pool,int counter,int grid,float time,float dt,int players,StarfallGpuBuffers stars,int roster,int dispatch) {
        if(!stars.hasDome()||!stars.domeReady())return;
        var passes=programs.getOrDefault(program,new Programs(program,program,program,program));
        boolean legacy=passes.control()==passes.solve(); // Frozen before-build GPU fixtures only.
        stars.bind(program,true);int state=stars.candidateStateBuffer();
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,0,pool);GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,1,pool);GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,3,counter);GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,14,counter);GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,13,grid);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(gridProgram,GpuUniforms.loc(gridProgram,"uPopulationReadEnabled"),0);
        if(roster!=0)GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,ParticleBuffers.POPULATION_READ_BB,roster);
        GpuUniforms.setIntUniform(gridProgram,"uCandidateStormRoster",roster!=0?1:0);
        for(int part:related(program))GpuUniforms.setIntUniform(part,"uCandidateStormRoster",roster!=0?1:0);
        for(int part:related(program)){stars.bindUniforms(part,true);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1f(part,GpuUniforms.loc(part,"uTimeSec"),time);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1f(part,GpuUniforms.loc(part,"uDt"),dt);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(part,GpuUniforms.loc(part,"uPlayerCount"),players);}
        com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(passes.control());com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(passes.control(),GpuUniforms.loc(passes.control(),"uMode"),4);ParticleDiagnostics.INSTANCE.call();com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute(1,1,1);barrier();
        int oldIndirect=dispatch>=0?dispatch:GL11.glGetInteger(GL43.GL_DISPATCH_INDIRECT_BUFFER_BINDING);GpuBindings.bind(GL43.GL_DISPATCH_INDIRECT_BUFFER,state);
        try {
            long full=(StarfallGpuBuffers.DOME_CONTACT_ARGS+4)*4L;
            GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,grid);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.clearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,0,ParticleBuffers.GRID_TABLE*4L,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(java.nio.IntBuffer)null);barrier();
            com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(gridProgram);indirect(full);
            GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,0,original);GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,14,previous);
            if(legacy)com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(program,GpuUniforms.loc(program,"uMode"),0);
            com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(passes.solve());indirect(full);
            if(legacy)com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(program,GpuUniforms.loc(program,"uMode"),3);
            com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(passes.apply());indirect(full);
            if(legacy)com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(program,GpuUniforms.loc(program,"uMode"),5);
            com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(passes.audit());indirect(full);
            com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(passes.control());com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(passes.control(),GpuUniforms.loc(passes.control(),"uMode"),6);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute(4,1,1);barrier();
        } finally {
            GpuUniforms.setIntUniform(gridProgram,"uCandidateStormRoster",0);
            GpuBindings.bind(GL43.GL_DISPATCH_INDIRECT_BUFFER,oldIndirect);
        }
    }
}
