package com.iridium126.createmanaindustry.client.packages.feedback;


import com.iridium126.createmanaindustry.client.packages.world.PackageCollisionGpu;


import com.iridium126.createmanaindustry.client.gpu.GpuCapabilities;

import java.nio.*;
import java.util.function.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Reliable per-lifecycle environmental journal. GPU backpressure pauses only full bodies. */
public final class PackageEnvironmentGpu implements AutoCloseable {
    public static final int HEADER_BYTES=64,SAMPLE_BYTES=48,HISTORY=20,EVENT_BYTES=1024,MAX_EVENTS=512;
    /** Logical history + motion count; physical offsets are defined by PackageEnvironmentLayout. */
    public static final int SAMPLE_STRIDE=HISTORY+1;
    private static final int OUTPUT_BYTES=16+MAX_EVENTS*EVENT_BYTES;
    private final int capacity,headers,captureStamps,stepProgram,captureProgram,ackProgram,pagesProgram,maximumPages;
    private int samples,pageCapacity;
    private final PackageEnvironmentLayout layout;
    private final com.iridium126.createmanaindustry.client.gpu.buffer.GpuGrowingBuffer historyStorage=new com.iridium126.createmanaindustry.client.gpu.buffer.GpuGrowingBuffer();
    private int requestedPages;
    private final int pageAction,pageFirst,pageCount,pageBody;
    private int ackBuffer;
    private final PackageEnvironmentAckBatch acknowledgements=new PackageEnvironmentAckBatch();
    private boolean batchingAcknowledgements;
    private boolean captureDemandDirty;
    private int ackBodies;
    private final JournalUniforms stepUniforms,captureUniforms,ackUniforms;
    private static final class JournalUniforms {
        final int captureSerial,coalesceTicks,count,environmentReady,eventCapacity,freezeDiagnostics,freezeStep,freshOnly,scanOffset,simulationStep,worldOriginSection,worldReady,worldShapeCapacity,worldSlotWords,worldTableMask;
        final int[] world;
        JournalUniforms(int program){
            captureSerial=GL20.glGetUniformLocation(program,"uCaptureSerial");
            coalesceTicks=GL20.glGetUniformLocation(program,"uCoalesceTicks");
            count=GL20.glGetUniformLocation(program,"uCount");
            environmentReady=GL20.glGetUniformLocation(program,"uEnvironmentReady");
            eventCapacity=GL20.glGetUniformLocation(program,"uEventCapacity");
            freezeDiagnostics=GL20.glGetUniformLocation(program,"uFreezeDiagnostics");
            freezeStep=GL20.glGetUniformLocation(program,"uFreezeStep");
            freshOnly=GL20.glGetUniformLocation(program,"uFreshOnly");
            scanOffset=GL20.glGetUniformLocation(program,"uScanOffset");
            simulationStep=GL20.glGetUniformLocation(program,"uSimulationStep");
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
    public void climbingBounds(boolean fullBounds){open();GL41.glProgramUniform1i(stepProgram,GL20.glGetUniformLocation(stepProgram,"uFullBoundingBoxLadders"),fullBounds?1:0);}
    public PackageEnvironmentGpu(int capacity,Function<String,String> sources){this(capacity,capacity,sources);}
    /** A bounded page limit permits local pressure without changing the wire or body capacity. */
    public PackageEnvironmentGpu(int capacity,int maximumPages,Function<String,String> sources){
        layout=new PackageEnvironmentLayout(capacity);
        if(maximumPages<0||maximumPages>capacity)throw new IllegalArgumentException("Environment history pages");
        this.capacity=capacity;this.maximumPages=maximumPages;
        int bootstrapPages=Math.min(MAX_EVENTS,maximumPages);
        int h=0,stamps=0,p=0,c=0,a=0,pages=0;PackageReadbackRing r=null;
        try{
            h=buffer((long)capacity*HEADER_BYTES);samples=historyStorage.ensure(layout.storageBytes(bootstrapPages));stamps=buffer((long)capacity*4);
            String diagnosticPrefix=PackageFreezeDiagnosticsGpu.supported()?"#define CMI_FREEZE_DIAGNOSTICS\n":"";
            p=com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler.compute("PackageEnvironmentGpu", "#version 450 core\n"+diagnosticPrefix+sources.apply("packages/environment.comp"));c=com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler.compute("PackageEnvironmentGpu", "#version 450 core\n"+diagnosticPrefix+sources.apply("packages/environment_capture.comp"));
            a=com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler.compute("PackageEnvironmentGpu", "#version 450 core\n"+sources.apply("packages/environment_ack.comp"));pages=com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler.compute("PackageEnvironmentGpu", "#version 450 core\n"+sources.apply("packages/environment_pages.comp"));
            r=new PackageReadbackRing(OUTPUT_BYTES);clear(h,0,(long)capacity*HEADER_BYTES);clear(stamps,0,(long)capacity*4);
        }catch(RuntimeException failure){if(r!=null)r.close();historyStorage.close();for(int id:new int[]{h,stamps})if(id!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(id);for(int id:new int[]{p,c,a,pages})if(id!=0)com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms.delete(id);throw failure;}
        headers=h;captureStamps=stamps;stepProgram=p;captureProgram=c;ackProgram=a;pagesProgram=pages;ring=r;
        stepUniforms=new JournalUniforms(p);captureUniforms=new JournalUniforms(c);ackUniforms=new JournalUniforms(a);
        pageAction=GL20.glGetUniformLocation(pages,"uPageAction");pageFirst=GL20.glGetUniformLocation(pages,"uFirst");
        pageCount=GL20.glGetUniformLocation(pages,"uCount");pageBody=GL20.glGetUniformLocation(pages,"uBody");
        // One bounded capture cohort can advance before the first asynchronous poll.
        // Pages remain unowned until a body actually has an unconfirmed suffix.
        initializePages(0,bootstrapPages);pageCapacity=bootstrapPages;
    }
    private long pendingOffset(){return (long)layout.pendingSample()*SAMPLE_BYTES;}
    private int pendingBytes(){return layout.pendingSamples()*SAMPLE_BYTES;}
    private void initializePages(int first,int count){
        if(count==0)return;
        GL20.glUseProgram(pagesProgram);bindStorage();GL20.glUniform1i(pageAction,0);
        GL30.glUniform1ui(pageFirst,first);GL30.glUniform1ui(pageCount,count);
        PackageCpuDiagnostics.dispatch();GL43.glDispatchCompute((count+63)/64,1,1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
    }
    private void resetHistory(int body){
        layout.workspaceSample(body);
        GL20.glUseProgram(pagesProgram);bindStorage();GL20.glUniform1i(pageAction,1);GL30.glUniform1ui(pageBody,body);
        PackageCpuDiagnostics.dispatch();GL43.glDispatchCompute(1,1,1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
    }
    private void growHistory(){
        historyStorage.poll();int needed=Math.min(maximumPages,requestedPages);
        if(needed<=pageCapacity)return;
        int next=Math.min(maximumPages,Math.max(16,pageCapacity));while(next<needed)next=Math.min(maximumPages,next*2);
        samples=historyStorage.ensure(layout.storageBytes(next));initializePages(pageCapacity,next-pageCapacity);pageCapacity=next;
    }
    private void seedPending(int count){
        // Lifecycle upload can restore nonempty journals. Seed its bounded prefix;
        // the first capture prunes empty slots. Steady-state marks come from the GPU.
        var seed=BufferUtils.createByteBuffer(pendingBytes());
        for(int word=0;word<(count+31)/32;word++)seed.putInt((word/4)*SAMPLE_BYTES+(word%4)*4,-1);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,samples);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,pendingOffset(),seed);
    }
    private static int buffer(long bytes){
        if(bytes>GpuCapabilities.current().storageBlockBytes())throw new IllegalStateException("Environment storage exceeds device limit");
        int id=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.allocate(GL43.GL_SHADER_STORAGE_BUFFER,bytes,GL15.GL_DYNAMIC_COPY);
        if(GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)!=bytes){com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(id);throw new IllegalStateException("Environment allocation failed");}return id;
    }
    private static void clear(int buffer,long first,long bytes){
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);
        try(var stack=MemoryStack.stackPush()){GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,first,bytes,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));}
    }
    /** The collision-only fall value shares the existing Links binding, keeping constraints at <=8 SSBOs. */
    public void motionMirror(int buffer,int offset){
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
        open();if(body<0||body>=capacity||id<=0||generation<=0||lease<=0||index<0||index==Integer.MAX_VALUE||revision<=0||fire< -20||!Float.isFinite(health))throw new IllegalArgumentException("Environment lifecycle");
        resetHistory(body);
        baseline.clear();for(int i=0;i<HEADER_BYTES;i+=4)baseline.putInt(i,0);
        baseline.putLong(0,id).putLong(8,generation).putLong(16,lease).putInt(32,fire).putInt(40,index+1).putInt(44,permissions).putFloat(48,health).putLong(56,revision);
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,headers);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,(long)body*HEADER_BYTES,baseline);
        clear(captureStamps,(long)body*4,4);
        clearMirror(body,1);
    }
    public void upload(ByteBuffer data,int count){
        open();if(!data.isDirect()||count<0||count>capacity||data.remaining()!=count*HEADER_BYTES)throw new IllegalArgumentException("Environment upload layout");
        clear(headers,0,(long)capacity*HEADER_BYTES);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,headers);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,0,data);
        clear(samples,0,layout.storageBytes(pageCapacity));initializePages(0,pageCapacity);
        captureDemandDirty=false;
        seedPending(count);
        clear(captureStamps,0,(long)capacity*4);
        clearMirror(0,capacity);
    }
    public void retire(int body){open();resetHistory(body);clear(headers,(long)body*HEADER_BYTES,HEADER_BYTES);clear(captureStamps,(long)body*4,4);clearMirror(body,1);}
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
            long offset=(long)layout.workspaceSample(body)*SAMPLE_BYTES;
            // timeline.y owns the page mapping and must survive timer-only restoration.
            bytes.limit(20);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,offset,bytes);
            bytes.limit(SAMPLE_BYTES).position(24);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,offset+24,bytes);
            if(motionMirror!=0){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,motionMirror);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,((long)motionOffset+body)*4,stack.floats(fall));}
        }
    }
    public void bindStorage(){open();GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,13,headers);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,14,samples);}
    public void bind(int readyLocation){bindStorage();GL20.glUniform1i(readyLocation,1);}
    public void step(int bodies,int count,PackageCollisionGpu.View world){
        step(bodies,count,world,false);
    }
    public void step(int bodies,int count,PackageCollisionGpu.View world,boolean mirrorAlreadyBound){
        open();if(count<0||count>capacity)throw new IllegalArgumentException("Environment step range");if(count==0)return;GL20.glUseProgram(stepProgram);bind(stepUniforms.environmentReady);
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
        open();if(count<0||count>capacity)throw new IllegalArgumentException("Environment capture range");if(count==0)return false;
        int output=ring.writableCaptureBuffer();if(output==0)return false;
        // Successful captures drain the device counter in their second pass.
        // If submission was interrupted, discard only that scratch statistic.
        if(captureDemandDirty){clear(samples,(long)layout.allocatorSample()*SAMPLE_BYTES+4,4);captureDemandDirty=false;}
        clear(output,0,16);GL20.glUseProgram(captureProgram);bind(captureUniforms.environmentReady);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,6,output);GL30.glUniform1ui(captureUniforms.count,count);
        int words=(count+31)/32;
        GL30.glUniform1ui(captureUniforms.scanOffset,scanOffset%words);scanOffset=(scanOffset+(MAX_EVENTS+31)/32)%words;
        GL30.glUniform1ui(captureUniforms.eventCapacity,MAX_EVENTS);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,7,captureStamps);
        GL30.glUniform1ui(captureUniforms.captureSerial,(int)(sequence+1));
        int fresh=captureUniforms.freshOnly;
        // Unreported suffixes take the bounded queue first. Retransmits fill spare
        // slots, so lost/delayed ACKs remain recoverable without starving new journals.
        GL20.glUniform1i(fresh,1);
        captureDemandDirty=true;
        {PackageCpuDiagnostics.dispatch();GL43.glDispatchCompute((words+63)/64,1,1);}GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        GL20.glUniform1i(fresh,0);
        {PackageCpuDiagnostics.dispatch();GL43.glDispatchCompute((words+63)/64,1,1);}GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        captureDemandDirty=false;
        return ring.submitCounted(output,16,EVENT_BYTES,MAX_EVENTS,1,sequence++);
    }
    public void poll(Consumer<ByteBuffer> consumer){
        open();requestedPages=0;ring.poll(1,snapshot->{var bytes=snapshot.bytes().order(ByteOrder.nativeOrder());int n=Math.min(MAX_EVENTS,bytes.getInt(0));
            if(n<0)throw new IllegalStateException("Environment event count");
            int demand=bytes.getInt(4),pages=bytes.getInt(8),free=bytes.getInt(12);
            if(pages<0||pages>maximumPages||free<0||free>pages||demand<0||demand>capacity-(pages-free))
                throw new IllegalStateException("Environment page accounting");
            requestedPages=Math.max(requestedPages,pages-free+demand);
            for(int i=0;i<n;i++){
                int first=16+i*EVENT_BYTES;var event=bytes.slice(first,EVENT_BYTES).order(ByteOrder.nativeOrder());
                int body=event.getInt(52);if(body<0||body>=capacity)throw new IllegalStateException("Environment capture identity");
                consumer.accept(event);
            }
            completed=snapshot.sequence();});
        // Captures are independent byte snapshots. Growth copies live GPU state and
        // retires old storage without waiting or changing any captured page reference.
        growHistory();
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
        acknowledge(body,through,fire,health,permissions,bodies,correction,motion,ox,oy,oz,halfHeight,0,0,0,0,0);
    }
    /** Production ACKs carry the exact lifecycle again, checked on the GPU at application time. */
    public void acknowledge(int body,long through,int fire,float health,int permissions,int bodies,
            com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageAuthorityRegion.Snapshot correction,
            com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageEnvironmentMotion motion,
            double ox,double oy,double oz,float halfHeight,long id,long generation,long lease,int index,long revision){
        open();if(body<0||body>=capacity||through<0||through>0xffff_ffffL||fire< -20||!Float.isFinite(health)||(permissions&~31)!=0
                ||id<0||id!=0&&(generation<=0||lease<=0||index<0||revision<=0)
                ||correction!=null&&(bodies<=0||motion==null))throw new IllegalArgumentException("Environment ACK range");
        if(acknowledgements.full()||bodies!=0&&ackBodies!=0&&ackBodies!=bodies)flushAcknowledgements();
        if(bodies!=0)ackBodies=bodies;
        int at=acknowledgements.append(body,through,fire,health,permissions,id,generation,lease,index,revision,correction!=null);
        if(correction!=null){
            var pose=correction.pose();var bytes=acknowledgements.fields();
            bytes.putFloat(at+68,motion.fallDistance()).putInt(at+72,motion.frozenTicks()).putInt(at+76,motion.age());
            bytes.putFloat(at+80,(float)(pose.x()-ox)).putFloat(at+84,(float)(pose.y()-oy)+halfHeight).putFloat(at+88,(float)(pose.z()-oz)).putFloat(at+92,pose.yaw());
            bytes.putFloat(at+96,pose.vx()).putFloat(at+100,pose.vy()).putFloat(at+104,pose.vz()).putFloat(at+108,(correction.flags()&1)!=0?1:0);
            bytes.putFloat(at+112,motion.stuckX()).putFloat(at+116,motion.stuckY()).putFloat(at+120,motion.stuckZ());
        }
        if(!batchingAcknowledgements)flushAcknowledgements();
    }
    /** Bound one network drain. Repeated body ACKs are ordered, never concurrently written. */
    public void beginAcknowledgements(){open();if(batchingAcknowledgements)throw new IllegalStateException("Nested environment ACK batch");batchingAcknowledgements=true;}
    public void endAcknowledgements(){open();if(!batchingAcknowledgements)throw new IllegalStateException("No environment ACK batch");try{flushAcknowledgements();}finally{batchingAcknowledgements=false;}}
    private void flushAcknowledgements(){
        int count=acknowledgements.size();if(count==0)return;
        if(ackBuffer==0)ackBuffer=buffer((long)PackageEnvironmentAckBatch.LIMIT*PackageEnvironmentAckBatch.ROW_BYTES);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,ackBuffer);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,0,acknowledgements.upload());
        GL20.glUseProgram(ackProgram);bind(ackUniforms.environmentReady);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,ackBuffer);
        if(ackBodies!=0)GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,ackBodies);
        if(motionMirror!=0)GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,3,motionMirror);
        GL30.glUniform1ui(ackUniforms.count,count);
        {PackageCpuDiagnostics.dispatch();GL43.glDispatchCompute((count+63)/64,1,1);}
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        acknowledgements.clear();ackBodies=0;
    }
    public int pending(){return ring.pending();}
    public long bytes(){return (long)capacity*(HEADER_BYTES+4)+historyStorage.capacityBytes()+OUTPUT_BYTES*PackageReadbackRing.SLOTS+(ackBuffer==0?0L:(long)PackageEnvironmentAckBatch.LIMIT*PackageEnvironmentAckBatch.ROW_BYTES);}
    public int headerBuffer(){open();return headers;}
    private void open(){if(closed)throw new IllegalStateException("Environment journal closed");}
    @Override public void close(){if(closed)return;closed=true;ring.close();com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(headers);historyStorage.close();com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(captureStamps);if(ackBuffer!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(ackBuffer);com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms.delete(stepProgram);com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms.delete(captureProgram);com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms.delete(ackProgram);com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms.delete(pagesProgram);}
}
