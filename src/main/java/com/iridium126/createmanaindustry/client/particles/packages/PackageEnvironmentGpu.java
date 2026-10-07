package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.*;
import java.util.function.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Reliable per-lifecycle environmental journal. GPU backpressure pauses only full bodies. */
public final class PackageEnvironmentGpu implements AutoCloseable {
    public static final int HEADER_BYTES=64,SAMPLE_BYTES=48,HISTORY=20,EVENT_BYTES=1024,MAX_EVENTS=512;
    private static final int OUTPUT_BYTES=16+MAX_EVENTS*EVENT_BYTES;
    private final int capacity,headers,samples,captureStamps,output,stepProgram,captureProgram,ackProgram;
    private final PackageReadbackRing ring;
    private final ByteBuffer baseline=BufferUtils.createByteBuffer(HEADER_BYTES);
    private long simulationStep,sequence,completed=-1;
    public long barrier(){return sequence-1;}
    public boolean drained(long barrier){return completed>=barrier;}
    private int scanOffset;
    private int coalesceTicks=1;
    public void tickRate(double rate){open();coalesceTicks=com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageTickTiming.historyTicks(rate);}
    private boolean closed;
    private PackageFreezeDiagnosticsGpu freezeDiagnostics;
    public void freezeDiagnostics(PackageFreezeDiagnosticsGpu diagnostics){freezeDiagnostics=diagnostics;}
    public PackageEnvironmentGpu(int capacity,Function<String,String> sources){
        if(capacity<1||capacity>131072)throw new IllegalArgumentException("Environment capacity");this.capacity=capacity;
        int h=0,s=0,stamps=0,o=0,p=0,c=0,a=0;PackageReadbackRing r=null;
        try{
            h=buffer((long)capacity*HEADER_BYTES);s=buffer((long)capacity*HISTORY*SAMPLE_BYTES);stamps=buffer((long)capacity*4);o=buffer(OUTPUT_BYTES);
            String diagnosticPrefix=PackageFreezeDiagnosticsGpu.supported()?"#define CMI_FREEZE_DIAGNOSTICS\n":"";
            p=compile(diagnosticPrefix+sources.apply("packages/environment.comp"));c=compile(diagnosticPrefix+sources.apply("packages/environment_capture.comp"));
            a=compile(sources.apply("packages/environment_ack.comp"));r=new PackageReadbackRing(OUTPUT_BYTES);clear(h,0,(long)capacity*HEADER_BYTES);clear(stamps,0,(long)capacity*4);
        }catch(RuntimeException failure){if(r!=null)r.close();for(int id:new int[]{h,s,stamps,o})if(id!=0)GL15.glDeleteBuffers(id);for(int id:new int[]{p,c,a})if(id!=0)GL20.glDeleteProgram(id);throw failure;}
        headers=h;samples=s;captureStamps=stamps;output=o;stepProgram=p;captureProgram=c;ackProgram=a;ring=r;
    }
    private static int buffer(long bytes){
        if(bytes>GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE))throw new IllegalStateException("Environment storage exceeds device limit");
        int id=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,bytes,GL15.GL_DYNAMIC_COPY);
        if(GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)!=bytes){GL15.glDeleteBuffers(id);throw new IllegalStateException("Environment allocation failed");}return id;
    }
    private static int compile(String source){
        int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER),program=GL20.glCreateProgram();
        try{GL20.glShaderSource(shader,"#version 450 core\n"+source);GL20.glCompileShader(shader);
            if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
            GL20.glAttachShader(program,shader);GL20.glLinkProgram(program);
            if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(program));return program;
        }catch(RuntimeException error){GL20.glDeleteProgram(program);throw error;}finally{GL20.glDeleteShader(shader);}
    }
    private static void clear(int buffer,long first,long bytes){
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);
        try(var stack=MemoryStack.stackPush()){GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,first,bytes,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));}
    }
    public void reset(int body,long id,long generation,long lease,int fire,float health,int permissions){reset(body,id,generation,lease,0,1,fire,health,permissions);}
    public void reset(int body,long id,long generation,long lease,int index,long revision,int fire,float health,int permissions){
        open();if(body<0||body>=capacity||id<=0||generation<=0||lease<=0||fire<0||!Float.isFinite(health))throw new IllegalArgumentException("Environment lifecycle");
        baseline.clear();for(int i=0;i<HEADER_BYTES;i+=4)baseline.putInt(i,0);
        baseline.putLong(0,id).putLong(8,generation).putLong(16,lease).putInt(32,fire).putInt(40,index+1).putInt(44,permissions).putFloat(48,health).putLong(56,revision);
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,headers);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,(long)body*HEADER_BYTES,baseline);
        clear(samples,(long)body*HISTORY*SAMPLE_BYTES,(long)HISTORY*SAMPLE_BYTES);
        clear(captureStamps,(long)body*4,4);
    }
    public void upload(ByteBuffer data,int count){
        open();if(!data.isDirect()||count<0||count>capacity||data.remaining()!=count*HEADER_BYTES)throw new IllegalArgumentException("Environment upload layout");
        clear(headers,0,(long)capacity*HEADER_BYTES);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,headers);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,data);
        clear(captureStamps,0,(long)capacity*4);
    }
    public void retire(int body){open();clear(headers,(long)body*HEADER_BYTES,HEADER_BYTES);clear(captureStamps,(long)body*4,4);}
    public void bind(int readyLocation){open();GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,13,headers);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,14,samples);GL20.glUniform1i(readyLocation,1);}
    public void step(int bodies,int count,PackageCollisionGpu.View world){
        open();if(count==0)return;GL20.glUseProgram(stepProgram);bind(GL20.glGetUniformLocation(stepProgram,"uEnvironmentReady"));
        int freezeReady=GL20.glGetUniformLocation(stepProgram,"uFreezeDiagnostics"),freezeStep=GL20.glGetUniformLocation(stepProgram,"uFreezeStep");
        if(freezeDiagnostics==null)GL20.glUniform1i(freezeReady,0);else freezeDiagnostics.bind(freezeReady,freezeStep);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,bodies);GL30.glUniform1ui(GL20.glGetUniformLocation(stepProgram,"uCount"),count);
        long step=++simulationStep;GL30.glUniform2ui(GL20.glGetUniformLocation(stepProgram,"uSimulationStep"),(int)step,(int)(step>>>32));
        GL30.glUniform1ui(GL20.glGetUniformLocation(stepProgram,"uCoalesceTicks"),coalesceTicks);
        world.bind(new int[]{GL20.glGetUniformLocation(stepProgram,"uWorldReady"),GL20.glGetUniformLocation(stepProgram,"uWorldOriginSection"),
            GL20.glGetUniformLocation(stepProgram,"uWorldTableMask"),GL20.glGetUniformLocation(stepProgram,"uWorldSlotWords"),GL20.glGetUniformLocation(stepProgram,"uWorldShapeCapacity")},0,true);
        GL43.glDispatchCompute((count+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }
    public boolean capture(int count){
        open();if(count==0||ring.pending()==PackageReadbackRing.SLOTS)return false;
        clear(output,0,16);GL20.glUseProgram(captureProgram);bind(GL20.glGetUniformLocation(captureProgram,"uEnvironmentReady"));
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,6,output);GL30.glUniform1ui(GL20.glGetUniformLocation(captureProgram,"uCount"),count);
        GL30.glUniform1ui(GL20.glGetUniformLocation(captureProgram,"uScanOffset"),scanOffset%count);scanOffset=(scanOffset+MAX_EVENTS)%count;
        GL30.glUniform1ui(GL20.glGetUniformLocation(captureProgram,"uEventCapacity"),MAX_EVENTS);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,7,captureStamps);
        GL30.glUniform1ui(GL20.glGetUniformLocation(captureProgram,"uCaptureSerial"),(int)(sequence+1));
        int fresh=GL20.glGetUniformLocation(captureProgram,"uFreshOnly");
        // Unreported suffixes take the bounded queue first. Retransmits fill spare
        // slots, so lost/delayed ACKs remain recoverable without starving new journals.
        GL20.glUniform1i(fresh,1);
        GL43.glDispatchCompute((count+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        GL20.glUniform1i(fresh,0);
        GL43.glDispatchCompute((count+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        return ring.submit(output,1,sequence++);
    }
    public void poll(Consumer<ByteBuffer> consumer){
        open();ring.poll(1,snapshot->{completed=snapshot.sequence();var bytes=snapshot.bytes().order(ByteOrder.nativeOrder());int n=Math.min(MAX_EVENTS,bytes.getInt(0));
            if(n<0)throw new IllegalStateException("Environment event count");
            for(int i=0;i<n;i++){int first=16+i*EVENT_BYTES;consumer.accept(bytes.slice(first,EVENT_BYTES).order(ByteOrder.nativeOrder()));}});
    }
    /** Caller validates exact identity/lease before updating this slot; late ACKs never select by slot alone. */
    public void acknowledge(int body,long through,int fire,float health){acknowledge(body,through,fire,health,7);}
    public void acknowledge(int body,long through,int fire,float health,int permissions){
        open();if(body<0||body>=capacity||through<0||through>0xffff_ffffL||fire<0||!Float.isFinite(health)||(permissions&~7)!=0)throw new IllegalArgumentException("Environment ACK range");
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);GL20.glUseProgram(ackProgram);bind(GL20.glGetUniformLocation(ackProgram,"uEnvironmentReady"));
        GL30.glUniform1ui(GL20.glGetUniformLocation(ackProgram,"uBody"),body);GL30.glUniform1ui(GL20.glGetUniformLocation(ackProgram,"uThrough"),(int)through);
        GL30.glUniform1ui(GL20.glGetUniformLocation(ackProgram,"uFire"),fire);GL30.glUniform1ui(GL20.glGetUniformLocation(ackProgram,"uPermissions"),permissions);
        GL20.glUniform1f(GL20.glGetUniformLocation(ackProgram,"uHealth"),health);GL43.glDispatchCompute(1,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }
    public int pending(){return ring.pending();}
    public long bytes(){return (long)capacity*(HEADER_BYTES+HISTORY*SAMPLE_BYTES+4)+OUTPUT_BYTES*(1+PackageReadbackRing.SLOTS);}
    public int headerBuffer(){open();return headers;}
    private void open(){if(closed)throw new IllegalStateException("Environment journal closed");}
    @Override public void close(){if(closed)return;closed=true;ring.close();GL15.glDeleteBuffers(headers);GL15.glDeleteBuffers(samples);GL15.glDeleteBuffers(captureStamps);GL15.glDeleteBuffers(output);GL20.glDeleteProgram(stepProgram);GL20.glDeleteProgram(captureProgram);GL20.glDeleteProgram(ackProgram);}
}
