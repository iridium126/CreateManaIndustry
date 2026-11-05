package com.iridium126.createmanaindustry.client.packages.feedback;

import com.iridium126.createmanaindustry.client.gpu.state.GpuBindings;


import com.iridium126.createmanaindustry.client.gpu.GpuCapabilities;

import java.util.function.Consumer;
import java.util.function.Function;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Optional sidecar, sampled at most once/second through zero-timeout readback fences. */
public final class PackageFreezeDiagnosticsGpu implements AutoCloseable {
    private final int states,output,program;
    private final PackageReadbackRing ring;
    private final int capacity;
    private static final String[] CAPTURE_UNIFORMS={"uFreezeDiagnostics","uFreezeStep","uEnvironmentReady","uCount","uEventCapacity","uScanOffset"};
    private final int[] captureLocations=new int[CAPTURE_UNIFORMS.length];
    private long step,sequence,nextCapture;
    private int offset;
    private boolean closed;
    public static boolean supported(){return GpuCapabilities.current().storageBindings()>15
            &&GpuCapabilities.current().computeStorageBlocks()>=9;}
    public PackageFreezeDiagnosticsGpu(int capacity,Function<String,String> sources){
        if(!supported())throw new IllegalStateException("Device cannot fit the optional freeze diagnostic SSBO; base physics remains supported");
        this.capacity=capacity;int state=0,out=0,linked=0;PackageReadbackRing readback=null;
        try{
            state=buffer(Math.multiplyExact(capacity,PackageFreezeReport.STATE_BYTES));
            out=buffer(PackageFreezeReport.BYTES);clear(state,Math.multiplyExact(capacity,PackageFreezeReport.STATE_BYTES));
            linked=com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler.compute("Package freeze diagnostics","#version 450 core\n#define CMI_FREEZE_DIAGNOSTICS\n"+sources.apply("packages/freeze_capture.comp"));
            readback=new PackageReadbackRing(PackageFreezeReport.BYTES);
        }catch(RuntimeException failure){if(readback!=null)readback.close();for(int id:new int[]{state,out})if(id!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(id);if(linked!=0)com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms.delete(linked);throw failure;}
        states=state;output=out;program=linked;ring=readback;
        for(int i=0;i<captureLocations.length;i++)captureLocations[i]=GL20.glGetUniformLocation(program,CAPTURE_UNIFORMS[i]);
    }
    private static int buffer(int bytes){
        if(bytes<=0||bytes>GpuCapabilities.current().storageBlockBytes())throw new IllegalArgumentException("Freeze storage size");
        int id=GL15.glGenBuffers();if(id==0)throw new IllegalStateException("Freeze buffer allocation failed");
        try{GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,id);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.allocate(GL43.GL_SHADER_STORAGE_BUFFER,bytes,GL15.GL_DYNAMIC_COPY);
            if(GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)!=bytes)throw new IllegalStateException("Freeze buffer storage allocation failed");
            return id;
        }catch(RuntimeException failure){com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(id);throw failure;}
    }
    private static void clear(int id,int bytes){GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,id);try(var stack=MemoryStack.stackPush()){
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.clearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,0,bytes,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));
    }com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);}
    public void nextStep(){step++;}
    public void bind(int enabled,int timeline){
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,15,states);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current1i(enabled,1);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current2ui(timeline,(int)step,(int)(step>>>32));
    }
    public void reset(int first,int count){
        if(count==0)return;
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,states);
        try(var stack=MemoryStack.stackPush()){com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.clearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,
                (long)first*PackageFreezeReport.STATE_BYTES,(long)count*PackageFreezeReport.STATE_BYTES,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));}
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }
    public boolean capture(int bodies,int count,PackageEnvironmentGpu environment,long now){
        if(closed||count<0||count>capacity)throw new IllegalStateException("Freeze capture range/state");
        if(count==0||now<nextCapture||ring.pending()==PackageReadbackRing.SLOTS)return false;
        clear(output,PackageFreezeReport.EVENT_OFFSET);com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(program);
        bind(captureLocations[0],captureLocations[1]);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,0,bodies);GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,6,output);
        int envReady=captureLocations[2];
        if(environment==null)com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current1i(envReady,0);else environment.bind(envReady);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current1ui(captureLocations[3],count);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current1ui(captureLocations[4],PackageFreezeReport.MAX_EVENTS);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current1ui(captureLocations[5],offset%count);
        {PackageCpuDiagnostics.dispatch();com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute((count+63)/64,1,1);}com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        if(!ring.submit(output,1,sequence++))return false;
        offset=(offset+PackageFreezeReport.MAX_EVENTS)%count;nextCapture=now+1_000_000_000L;return true;
    }
    public void poll(Consumer<PackageFreezeReport.Event> events,Consumer<PackageFreezeReport.Summary> summaries){
        if(closed)return;ring.poll(1,snapshot->summaries.accept(PackageFreezeReport.decode(snapshot.bytes(),events)));
    }
    public int stateBuffer(){return states;}
    public long bytes(){return (long)capacity*PackageFreezeReport.STATE_BYTES+(long)PackageFreezeReport.BYTES*(PackageReadbackRing.SLOTS+1);}
    @Override public void close(){if(closed)return;closed=true;ring.close();com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(states);com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(output);com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms.delete(program);}
}
