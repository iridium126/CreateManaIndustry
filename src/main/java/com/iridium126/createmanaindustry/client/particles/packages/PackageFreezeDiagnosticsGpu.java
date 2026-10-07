package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.function.Consumer;
import java.util.function.Function;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Optional sidecar, sampled at most once/second through zero-timeout readback fences. */
public final class PackageFreezeDiagnosticsGpu implements AutoCloseable {
    private final int states,output,program;
    private final PackageReadbackRing ring;
    private final int capacity;
    private long step,sequence,nextCapture;
    private int offset;
    private boolean closed;
    public static boolean supported(){return GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS)>15
            &&GL11.glGetInteger(GL43.GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS)>=9;}
    public PackageFreezeDiagnosticsGpu(int capacity,Function<String,String> sources){
        if(!supported())throw new IllegalStateException("Device cannot fit the optional freeze diagnostic SSBO; base physics remains supported");
        this.capacity=capacity;int state=0,out=0,linked=0;PackageReadbackRing readback=null;
        try{
            state=buffer(Math.multiplyExact(capacity,PackageFreezeReport.STATE_BYTES));
            out=buffer(PackageFreezeReport.BYTES);clear(state,Math.multiplyExact(capacity,PackageFreezeReport.STATE_BYTES));
            int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
            try{
                GL20.glShaderSource(shader,"#version 450 core\n#define CMI_FREEZE_DIAGNOSTICS\n"+sources.apply("packages/freeze_capture.comp"));GL20.glCompileShader(shader);
                if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
                linked=GL20.glCreateProgram();GL20.glAttachShader(linked,shader);GL20.glLinkProgram(linked);
                if(GL20.glGetProgrami(linked,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(linked));
            }finally{GL20.glDeleteShader(shader);}
            readback=new PackageReadbackRing(PackageFreezeReport.BYTES);
        }catch(RuntimeException failure){if(readback!=null)readback.close();for(int id:new int[]{state,out})if(id!=0)GL15.glDeleteBuffers(id);if(linked!=0)GL20.glDeleteProgram(linked);throw failure;}
        states=state;output=out;program=linked;ring=readback;
    }
    private static int buffer(int bytes){
        if(bytes<=0||bytes>GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE))throw new IllegalArgumentException("Freeze storage size");
        int id=GL15.glGenBuffers();if(id==0)throw new IllegalStateException("Freeze buffer allocation failed");
        try{GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,bytes,GL15.GL_DYNAMIC_COPY);
            if(GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)!=bytes)throw new IllegalStateException("Freeze buffer storage allocation failed");
            return id;
        }catch(RuntimeException failure){GL15.glDeleteBuffers(id);throw failure;}
    }
    private static void clear(int id,int bytes){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);try(var stack=MemoryStack.stackPush()){
        GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,0,bytes,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));
    }GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);}
    public void nextStep(){step++;}
    public void bind(int enabled,int timeline){
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,15,states);GL20.glUniform1i(enabled,1);
        GL30.glUniform2ui(timeline,(int)step,(int)(step>>>32));
    }
    public void reset(int first,int count){
        if(count==0)return;
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,states);
        try(var stack=MemoryStack.stackPush()){GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,
                (long)first*PackageFreezeReport.STATE_BYTES,(long)count*PackageFreezeReport.STATE_BYTES,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));}
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }
    public boolean capture(int bodies,int count,PackageEnvironmentGpu environment,long now){
        if(closed||count<0||count>capacity)throw new IllegalStateException("Freeze capture range/state");
        if(count==0||now<nextCapture||ring.pending()==PackageReadbackRing.SLOTS)return false;
        clear(output,PackageFreezeReport.EVENT_OFFSET);GL20.glUseProgram(program);
        bind(GL20.glGetUniformLocation(program,"uFreezeDiagnostics"),GL20.glGetUniformLocation(program,"uFreezeStep"));
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,bodies);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,6,output);
        int envReady=GL20.glGetUniformLocation(program,"uEnvironmentReady");
        if(environment==null)GL20.glUniform1i(envReady,0);else environment.bind(envReady);
        GL30.glUniform1ui(GL20.glGetUniformLocation(program,"uCount"),count);
        GL30.glUniform1ui(GL20.glGetUniformLocation(program,"uEventCapacity"),PackageFreezeReport.MAX_EVENTS);
        GL30.glUniform1ui(GL20.glGetUniformLocation(program,"uScanOffset"),offset%count);
        GL43.glDispatchCompute((count+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        if(!ring.submit(output,1,sequence++))return false;
        offset=(offset+PackageFreezeReport.MAX_EVENTS)%count;nextCapture=now+1_000_000_000L;return true;
    }
    public void poll(Consumer<PackageFreezeReport.Event> events,Consumer<PackageFreezeReport.Summary> summaries){
        if(closed)return;ring.poll(1,snapshot->summaries.accept(PackageFreezeReport.decode(snapshot.bytes(),events)));
    }
    public int stateBuffer(){return states;}
    public long bytes(){return (long)capacity*PackageFreezeReport.STATE_BYTES+(long)PackageFreezeReport.BYTES*(PackageReadbackRing.SLOTS+1);}
    @Override public void close(){if(closed)return;closed=true;ring.close();GL15.glDeleteBuffers(states);GL15.glDeleteBuffers(output);GL20.glDeleteProgram(program);}
}
