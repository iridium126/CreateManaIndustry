package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.*;
import java.util.function.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Reliable per-lifecycle environmental journal. GPU backpressure pauses only full bodies. */
public final class PackageEnvironmentGpu implements AutoCloseable {
    public static final int HEADER_BYTES=64,SAMPLE_BYTES=48,HISTORY=20,EVENT_BYTES=1024,MAX_EVENTS=512;
    public static final int SAMPLE_STRIDE=HISTORY+1;
    private static final int OUTPUT_BYTES=16+MAX_EVENTS*EVENT_BYTES;
    private final int capacity,headers,samples,captureStamps,output,stepProgram,captureProgram,ackProgram;
    private final JournalUniforms stepUniforms,captureUniforms,ackUniforms;
    private static final class JournalUniforms {
        final int body,captureSerial,coalesceTicks,correctBody,correctPosition,correctVelocity,count,environmentReady,eventCapacity,fire,freezeDiagnostics,freezeStep,freshOnly,health,motionAge,motionFall,motionFrozen,motionStuck,permissions,scanOffset,simulationStep,through,worldOriginSection,worldReady,worldShapeCapacity,worldSlotWords,worldTableMask;
        final int[] world;
        JournalUniforms(int program){
            body=GL20.glGetUniformLocation(program,"uBody");
            captureSerial=GL20.glGetUniformLocation(program,"uCaptureSerial");
            coalesceTicks=GL20.glGetUniformLocation(program,"uCoalesceTicks");
            correctBody=GL20.glGetUniformLocation(program,"uCorrectBody");
            correctPosition=GL20.glGetUniformLocation(program,"uCorrectPosition");
            correctVelocity=GL20.glGetUniformLocation(program,"uCorrectVelocity");
            count=GL20.glGetUniformLocation(program,"uCount");
            environmentReady=GL20.glGetUniformLocation(program,"uEnvironmentReady");
            eventCapacity=GL20.glGetUniformLocation(program,"uEventCapacity");
            fire=GL20.glGetUniformLocation(program,"uFire");
            freezeDiagnostics=GL20.glGetUniformLocation(program,"uFreezeDiagnostics");
            freezeStep=GL20.glGetUniformLocation(program,"uFreezeStep");
            freshOnly=GL20.glGetUniformLocation(program,"uFreshOnly");
            health=GL20.glGetUniformLocation(program,"uHealth");
            motionAge=GL20.glGetUniformLocation(program,"uMotionAge");
            motionFall=GL20.glGetUniformLocation(program,"uMotionFall");
            motionFrozen=GL20.glGetUniformLocation(program,"uMotionFrozen");
            motionStuck=GL20.glGetUniformLocation(program,"uMotionStuck");
            permissions=GL20.glGetUniformLocation(program,"uPermissions");
            scanOffset=GL20.glGetUniformLocation(program,"uScanOffset");
            simulationStep=GL20.glGetUniformLocation(program,"uSimulationStep");
            through=GL20.glGetUniformLocation(program,"uThrough");
            worldOriginSection=GL20.glGetUniformLocation(program,"uWorldOriginSection");
            worldReady=GL20.glGetUniformLocation(program,"uWorldReady");
            worldShapeCapacity=GL20.glGetUniformLocation(program,"uWorldShapeCapacity");
            worldSlotWords=GL20.glGetUniformLocation(program,"uWorldSlotWords");
            worldTableMask=GL20.glGetUniformLocation(program,"uWorldTableMask");
            world=new int[]{worldReady,worldOriginSection,worldTableMask,worldSlotWords,worldShapeCapacity};
        }
    }
    private final PackageReadbackRing ring;
    private int motionMirror,motionOffset;
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
    void climbingBounds(boolean fullBounds){open();GL41.glProgramUniform1i(stepProgram,GL20.glGetUniformLocation(stepProgram,"uFullBoundingBoxLadders"),fullBounds?1:0);}
    public PackageEnvironmentGpu(int capacity,Function<String,String> sources){
        if(capacity<1||capacity>131072)throw new IllegalArgumentException("Environment capacity");this.capacity=capacity;
        int h=0,s=0,stamps=0,o=0,p=0,c=0,a=0;PackageReadbackRing r=null;
        try{
            h=buffer((long)capacity*HEADER_BYTES);s=buffer((long)capacity*SAMPLE_STRIDE*SAMPLE_BYTES);stamps=buffer((long)capacity*4);o=buffer(OUTPUT_BYTES);
            String diagnosticPrefix=PackageFreezeDiagnosticsGpu.supported()?"#define CMI_FREEZE_DIAGNOSTICS\n":"";
            p=compile(diagnosticPrefix+sources.apply("packages/environment.comp"));c=compile(diagnosticPrefix+sources.apply("packages/environment_capture.comp"));
            a=compile(sources.apply("packages/environment_ack.comp"));r=new PackageReadbackRing(OUTPUT_BYTES);clear(h,0,(long)capacity*HEADER_BYTES);clear(stamps,0,(long)capacity*4);
        }catch(RuntimeException failure){if(r!=null)r.close();for(int id:new int[]{h,s,stamps,o})if(id!=0)GL15.glDeleteBuffers(id);for(int id:new int[]{p,c,a})if(id!=0)GL20.glDeleteProgram(id);throw failure;}
        headers=h;samples=s;captureStamps=stamps;output=o;stepProgram=p;captureProgram=c;ackProgram=a;ring=r;
        stepUniforms=new JournalUniforms(p);captureUniforms=new JournalUniforms(c);ackUniforms=new JournalUniforms(a);
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
    /** The collision-only fall value shares the existing Links binding, keeping constraints at <=8 SSBOs. */
    void motionMirror(int buffer,int offset){
        open();if(buffer<=0||offset<0||motionMirror!=0)throw new IllegalArgumentException("Environment collision mirror");
        motionMirror=buffer;motionOffset=offset;clear(buffer,(long)offset*4,(long)capacity*4);
        for(int program:new int[]{stepProgram,ackProgram}){
            GL41.glProgramUniform1i(program,GL20.glGetUniformLocation(program,"uFallMirrorReady"),1);
            GL41.glProgramUniform1ui(program,GL20.glGetUniformLocation(program,"uFallMirrorOffset"),offset);
        }
    }
    private void clearMirror(int first,int count){if(motionMirror!=0)clear(motionMirror,((long)motionOffset+first)*4,(long)count*4);}
    public void reset(int body,long id,long generation,long lease,int fire,float health,int permissions){reset(body,id,generation,lease,0,1,fire,health,permissions);}
    public void reset(int body,long id,long generation,long lease,int index,long revision,int fire,float health,int permissions){
        open();if(body<0||body>=capacity||id<=0||generation<=0||lease<=0||fire< -20||!Float.isFinite(health))throw new IllegalArgumentException("Environment lifecycle");
        baseline.clear();for(int i=0;i<HEADER_BYTES;i+=4)baseline.putInt(i,0);
        baseline.putLong(0,id).putLong(8,generation).putLong(16,lease).putInt(32,fire).putInt(40,index+1).putInt(44,permissions).putFloat(48,health).putLong(56,revision);
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,headers);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,(long)body*HEADER_BYTES,baseline);
        clear(samples,(long)body*SAMPLE_STRIDE*SAMPLE_BYTES,(long)SAMPLE_STRIDE*SAMPLE_BYTES);
        clear(captureStamps,(long)body*4,4);
        clearMirror(body,1);
    }
    public void upload(ByteBuffer data,int count){
        open();if(!data.isDirect()||count<0||count>capacity||data.remaining()!=count*HEADER_BYTES)throw new IllegalArgumentException("Environment upload layout");
        clear(headers,0,(long)capacity*HEADER_BYTES);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,headers);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,data);
        clear(samples,0,(long)capacity*SAMPLE_STRIDE*SAMPLE_BYTES);
        clear(captureStamps,0,(long)capacity*4);
        clearMirror(0,capacity);
    }
    public void retire(int body){open();clear(headers,(long)body*HEADER_BYTES,HEADER_BYTES);clear(samples,(long)body*SAMPLE_STRIDE*SAMPLE_BYTES,(long)SAMPLE_STRIDE*SAMPLE_BYTES);clear(captureStamps,(long)body*4,4);clearMirror(body,1);}
    /** Mutation-time restoration of deterministic environment state, never a per-frame upload. */
    public void resetMotion(int body,int frozen,float fall,float stuckX,float stuckY,float stuckZ) {
        resetMotion(body,frozen,fall,stuckX,stuckY,stuckZ,0);
    }
    public void resetMotion(int body,int frozen,float fall,float stuckX,float stuckY,float stuckZ,int age) {
        open();if(body<0||body>=capacity||frozen<0||frozen>140||age<0||!Float.isFinite(fall)||fall<0
                ||!Float.isFinite(stuckX+stuckY+stuckZ)||Math.min(stuckX,Math.min(stuckY,stuckZ))<0)
            throw new IllegalArgumentException("Package environment motion baseline");
        try(var stack=MemoryStack.stackPush()) {
            var bytes=stack.calloc(SAMPLE_BYTES);bytes.putInt(0,frozen).putFloat(8,fall).putInt(16,age).putInt(28,1).putFloat(32,stuckX).putFloat(36,stuckY).putFloat(40,stuckZ);
            GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,samples);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,((long)body*SAMPLE_STRIDE+HISTORY)*SAMPLE_BYTES,bytes);
            if(motionMirror!=0){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,motionMirror);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,((long)motionOffset+body)*4,stack.floats(fall));}
        }
    }
    void bindStorage(){open();GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,13,headers);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,14,samples);}
    public void bind(int readyLocation){bindStorage();GL20.glUniform1i(readyLocation,1);}
    public void step(int bodies,int count,PackageCollisionGpu.View world){
        step(bodies,count,world,false);
    }
    void step(int bodies,int count,PackageCollisionGpu.View world,boolean mirrorAlreadyBound){
        open();if(count==0)return;GL20.glUseProgram(stepProgram);bind(stepUniforms.environmentReady);
        int freezeReady=stepUniforms.freezeDiagnostics,freezeStep=stepUniforms.freezeStep;
        if(freezeDiagnostics==null)GL20.glUniform1i(freezeReady,0);else freezeDiagnostics.bind(freezeReady,freezeStep);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,bodies);GL30.glUniform1ui(stepUniforms.count,count);
        if(motionMirror!=0&&!mirrorAlreadyBound)GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,3,motionMirror);
        long step=++simulationStep;GL30.glUniform2ui(stepUniforms.simulationStep,(int)step,(int)(step>>>32));
        GL30.glUniform1ui(stepUniforms.coalesceTicks,coalesceTicks);
        world.bind(stepUniforms.world,0,true);
        {PackageCpuDiagnostics.dispatch();GL43.glDispatchCompute((count+63)/64,1,1);}GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }
    public boolean capture(int count){
        open();if(count==0||ring.pending()==PackageReadbackRing.SLOTS)return false;
        clear(output,0,16);GL20.glUseProgram(captureProgram);bind(captureUniforms.environmentReady);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,6,output);GL30.glUniform1ui(captureUniforms.count,count);
        GL30.glUniform1ui(captureUniforms.scanOffset,scanOffset%count);scanOffset=(scanOffset+MAX_EVENTS)%count;
        GL30.glUniform1ui(captureUniforms.eventCapacity,MAX_EVENTS);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,7,captureStamps);
        GL30.glUniform1ui(captureUniforms.captureSerial,(int)(sequence+1));
        int fresh=captureUniforms.freshOnly;
        // Unreported suffixes take the bounded queue first. Retransmits fill spare
        // slots, so lost/delayed ACKs remain recoverable without starving new journals.
        GL20.glUniform1i(fresh,1);
        {PackageCpuDiagnostics.dispatch();GL43.glDispatchCompute((count+63)/64,1,1);}GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        GL20.glUniform1i(fresh,0);
        {PackageCpuDiagnostics.dispatch();GL43.glDispatchCompute((count+63)/64,1,1);}GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
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
        acknowledge(body,through,fire,health,permissions,0,null,null,0,0,0,0);
    }
    public void acknowledge(int body,long through,int fire,float health,int permissions,int bodies,
            com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageAuthorityRegion.Snapshot correction,
            com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageEnvironmentMotion motion,
            double ox,double oy,double oz,float halfHeight){
        open();if(body<0||body>=capacity||through<0||through>0xffff_ffffL||fire< -20||!Float.isFinite(health)||(permissions&~31)!=0)throw new IllegalArgumentException("Environment ACK range");
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);GL20.glUseProgram(ackProgram);bind(ackUniforms.environmentReady);
        if(motionMirror!=0)GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,3,motionMirror);
        GL30.glUniform1ui(ackUniforms.body,body);GL30.glUniform1ui(ackUniforms.through,(int)through);
        GL20.glUniform1i(ackUniforms.correctBody,correction==null?0:1);
        if(correction!=null){
            var pose=correction.pose();GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,bodies);
            GL20.glUniform4f(ackUniforms.correctPosition,(float)(pose.x()-ox),(float)(pose.y()-oy)+halfHeight,(float)(pose.z()-oz),pose.yaw());
            GL20.glUniform4f(ackUniforms.correctVelocity,pose.vx(),pose.vy(),pose.vz(),(correction.flags()&1)!=0?1:0);
            GL30.glUniform1ui(ackUniforms.motionFrozen,motion.frozenTicks());
            GL30.glUniform1ui(ackUniforms.motionAge,motion.age());
            GL20.glUniform1f(ackUniforms.motionFall,motion.fallDistance());
            GL20.glUniform3f(ackUniforms.motionStuck,motion.stuckX(),motion.stuckY(),motion.stuckZ());
        }
        GL30.glUniform1ui(ackUniforms.fire,fire);GL30.glUniform1ui(ackUniforms.permissions,permissions);
        GL20.glUniform1f(ackUniforms.health,health);{PackageCpuDiagnostics.dispatch();GL43.glDispatchCompute(1,1,1);}GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }
    public int pending(){return ring.pending();}
    public long bytes(){return (long)capacity*(HEADER_BYTES+SAMPLE_STRIDE*SAMPLE_BYTES+4)+OUTPUT_BYTES*(1+PackageReadbackRing.SLOTS);}
    public int headerBuffer(){open();return headers;}
    private void open(){if(closed)throw new IllegalStateException("Environment journal closed");}
    @Override public void close(){if(closed)return;closed=true;ring.close();GL15.glDeleteBuffers(headers);GL15.glDeleteBuffers(samples);GL15.glDeleteBuffers(captureStamps);GL15.glDeleteBuffers(output);GL20.glDeleteProgram(stepProgram);GL20.glDeleteProgram(captureProgram);GL20.glDeleteProgram(ackProgram);}
}
