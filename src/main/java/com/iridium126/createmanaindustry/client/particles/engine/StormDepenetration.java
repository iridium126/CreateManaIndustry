package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.gpu.state.GpuBindings;

import com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers;
import com.iridium126.createmanaindustry.client.allaystorm.StormCollisionRuntime;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms;
import org.lwjgl.opengl.*;

/** Sparse corrections belong exclusively to the uncommitted output pool/bank. */
public final class StormDepenetration {
    private StormDepenetration() {}
    public static void run(int prepare,int schedule,int search,float time,StarfallGpuBuffers stars){
        if(!stars.navigationReady())return;
        run(prepare,schedule,search,time,stars,GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,29),
            GL11.glGetInteger(GL43.GL_DISPATCH_INDIRECT_BUFFER_BINDING));
    }
    public static void run(int prepare,int schedule,int search,float time,StarfallGpuBuffers stars,int storage,int indirect){
        if(!stars.navigationReady())return;
        stars.bind(search,true);
        for(int program:new int[]{prepare,schedule,search}){
            if(program!=search)stars.bindFrame(program,true);
            com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1f(program,GpuUniforms.loc(program,"uTimeSec"),time);
        }
        StormCollisionRuntime.bindCurrent(search);
        dispatch(prepare,schedule,search,stars,storage,indirect);
    }
    /** Standalone real-driver fixtures supply the same immutable collision publication. */
    public static void dispatch(int prepare,int schedule,int search,StarfallGpuBuffers stars){
        dispatch(prepare,schedule,search,stars,GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,29),
            GL11.glGetInteger(GL43.GL_DISPATCH_INDIRECT_BUFFER_BINDING));
    }
    private static void dispatch(int prepare,int schedule,int search,StarfallGpuBuffers stars,int previousStorage,int previous){
        stars.bindPathDispatch();
        com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(prepare);ParticleDiagnostics.INSTANCE.call();com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute(1,1,1);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL43.GL_COMMAND_BARRIER_BIT);
        GpuBindings.bind(GL43.GL_DISPATCH_INDIRECT_BUFFER,stars.domePathDispatchBuffer());
        try{com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(schedule);ParticleDiagnostics.INSTANCE.call();com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchComputeIndirect(0);}
        finally{GpuBindings.bind(GL43.GL_DISPATCH_INDIRECT_BUFFER,previous);GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,29,previousStorage);}
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(search);ParticleDiagnostics.INSTANCE.call();com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute(16,1,1);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }
}
