package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.function.Function;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Shared geometry/control per conveyor link, one event identity per package. GPU candidates are
 * retained until an exact ACK; capture pressure never discards them. Internal chain transport primitive. */
public final class PackageChainTrackGpu implements AutoCloseable {
    public static final int TRACK_BYTES=64,NODE_BYTES=16,META_BYTES=32,EVENT_BYTES=64,BANKS=4;
    public record Capture(int bank,int stamp,int headerBuffer,int recordBuffer,int capacity) {}
    private record Identity(long id,long generation) {}
    private static final String[] NAMES={"chain_nodes","chain_events","chain_events_finalize","chain_events_ack"};
    private static final String[] UNIFORMS={"uCount","uTrackCount","uNodeCount","uStep","uCapacity","uStamp","uRecords","uCancel","uUseHeader","uRecordOffset"};
    private final int capacity,trackCapacity,nodeCapacity;
    private final int[] programs=new int[4],headers=new int[BANKS],records=new int[BANKS];
    private final int[][] locations=new int[4][UNIFORMS.length];
    private final Capture[] captures=new Capture[BANKS];
    private final PackageGpuIdentityReservations identities=new PackageGpuIdentityReservations();
    private final int[] nodeFirst,nodeLength;
    private final long[] revisions;
    private final float[] thresholds;
    private final byte[] lifecycle;
    private final long[] candidateIdentities;
    private final int[] candidateTracks;
    private final Thread owner=Thread.currentThread();
    private int tracks,nodes,metadata,events,flights,ackRecords,count,trackCount,nodeCount;
    private long nextStep=1,nextStamp=1;
    private boolean closed;
    private final java.util.TreeSet<Integer> reusable=new java.util.TreeSet<>();

    public PackageChainTrackGpu(int capacity,int trackCapacity,int nodeCapacity,Function<String,String> sources) {
        if(capacity<1 || capacity>131072 || trackCapacity<1 || trackCapacity>131072 || nodeCapacity<1 || nodeCapacity>1_048_576
                || (capacity+63)/64>GL30.glGetIntegeri(GL43.GL_MAX_COMPUTE_WORK_GROUP_COUNT,0))
            throw new IllegalArgumentException("Chain track capacities");
        this.capacity=capacity;this.trackCapacity=trackCapacity;this.nodeCapacity=nodeCapacity;
        nodeFirst=new int[trackCapacity];nodeLength=new int[trackCapacity];revisions=new long[trackCapacity];thresholds=new float[nodeCapacity];
        lifecycle=new byte[capacity];
        candidateIdentities=new long[capacity*2];candidateTracks=new int[capacity];
        try {
            rebuild(sources);tracks=buffer((long)trackCapacity*TRACK_BYTES);nodes=buffer((long)nodeCapacity*NODE_BYTES);
            metadata=buffer((long)capacity*META_BYTES);events=buffer((long)capacity*EVENT_BYTES);flights=buffer((long)capacity*4);
            ackRecords=buffer((long)capacity*EVENT_BYTES);
            for(int i=0;i<BANKS;i++){headers[i]=buffer(16);records[i]=buffer((long)capacity*EVENT_BYTES);}
            clear(events);clear(flights);
        }catch(RuntimeException failed){close();throw failed;}
    }
    public void rebuild(Function<String,String> sources) {
        open();int[] next=new int[4];int[][] nextLocations=new int[4][UNIFORMS.length];
        try{for(int p=0;p<4;p++){next[p]=compile(sources.apply("packages/"+NAMES[p]+".comp"));
            for(int u=0;u<UNIFORMS.length;u++)nextLocations[p][u]=GL20.glGetUniformLocation(next[p],UNIFORMS[u]);}}
        catch(RuntimeException failed){for(int program:next)if(program!=0)GL20.glDeleteProgram(program);throw failed;}
        for(int p=0;p<4;p++){if(programs[p]!=0)GL20.glDeleteProgram(programs[p]);programs[p]=next[p];locations[p]=nextLocations[p];}
    }
    /** Epoch initialization only. Shared table updates must use replaceTrack with a newer revision. */
    public void uploadTables(ByteBuffer trackData,ByteBuffer nodeData) {
        open();if(count!=0)throw new IllegalStateException("Chain tables already in use");
        int nt=layout(trackData,TRACK_BYTES,trackCapacity),nn=layout(nodeData,NODE_BYTES,nodeCapacity);
        var tv=view(trackData);var nv=view(nodeData);
        for(int i=0;i<nn;i++) {
            int p=nv.position()+i*NODE_BYTES;float threshold=nv.getFloat(p);
            if(!Float.isFinite(threshold) || threshold<0 || (nv.getInt(p+4)&~7)!=0 || nv.getInt(p+4)==0 || nv.getInt(p+12)!=0)
                throw new IllegalArgumentException("Chain threshold/node flags");
        }
        for(int i=0;i<nt;i++)validateTrack(tv,tv.position()+i*TRACK_BYTES,nn,nv,0);
        upload(tracks,0,trackData);upload(nodes,0,nodeData);
        for(int i=0;i<nn;i++)thresholds[i]=nv.getFloat(nv.position()+i*NODE_BYTES);
        for(int i=0;i<nt;i++)remember(i,tv,tv.position()+i*TRACK_BYTES);
        trackCount=nt;nodeCount=nn;
    }
    public void appendTables(ByteBuffer trackData,ByteBuffer nodeData) {
        open();int nt=layout(trackData,TRACK_BYTES,trackCapacity-trackCount),nn=layout(nodeData,NODE_BYTES,nodeCapacity-nodeCount);
        var tv=view(trackData);var nv=view(nodeData);
        for(int i=0;i<nn;i++) {
            int p=nv.position()+i*NODE_BYTES;float threshold=nv.getFloat(p);
            if(!Float.isFinite(threshold) || threshold<0 || (nv.getInt(p+4)&~7)!=0 || nv.getInt(p+4)==0 || nv.getInt(p+12)!=0)
                throw new IllegalArgumentException("Chain appended threshold/node flags");
        }
        for(int i=0;i<nt;i++) {
            int p=tv.position()+i*TRACK_BYTES;
            if(tv.getInt(p+48)<nodeCount)throw new IllegalArgumentException("New chain track must own new node indices");
            validateTrack(tv,p,nodeCount+nn,nv,nodeCount);
        }
        upload(tracks,(long)trackCount*TRACK_BYTES,trackData);upload(nodes,(long)nodeCount*NODE_BYTES,nodeData);
        for(int i=0;i<nn;i++)thresholds[nodeCount+i]=nv.getFloat(nv.position()+i*NODE_BYTES);
        for(int i=0;i<nt;i++)remember(trackCount+i,tv,tv.position()+i*TRACK_BYTES);
        trackCount+=nt;nodeCount+=nn;
    }
    private void validateTrack(ByteBuffer data,int p,int nodes,ByteBuffer nodeData,int nodeOffset) {
        for(int j=0;j<12;j++)if(!Float.isFinite(data.getFloat(p+j*4)))throw new IllegalArgumentException("Non-finite chain geometry");
        float radius=data.getFloat(p+12),length=data.getFloat(p+28),rate=data.getFloat(p+32),loop=data.getFloat(p+36),reverse=data.getFloat(p+40);
        int first=data.getInt(p+48),n=data.getInt(p+52);long revision=data.getLong(p+56);
        if(radius<0 || length<0 || loop!=0 && loop!=1 || reverse!=0 && reverse!=1 || revision<=0
                || n<0 || n>32 || first<0 || (long)first+n>nodes || loop==0 && rate<0)
            throw new IllegalArgumentException("Chain geometry/control/version");
        if(loop==0) {
            double x=data.getFloat(p+16)-data.getFloat(p),y=data.getFloat(p+20)-data.getFloat(p+4),z=data.getFloat(p+24)-data.getFloat(p+8);
            double measured=Math.sqrt(x*x+y*y+z*z);
            if(Math.abs(measured-length)>Math.max(.0001,length*.00001))throw new IllegalArgumentException("Chain length differs from endpoints");
        }
        for(int i=0;i<n;i++) {
            float threshold=nodeData==null?thresholds[first+i]:nodeData.getFloat(nodeData.position()+(first+i-nodeOffset)*NODE_BYTES);
            if(loop==1?threshold>=360:threshold>length)throw new IllegalArgumentException("Node threshold outside track");
        }
    }
    private void remember(int i,ByteBuffer data,int p) {
        nodeFirst[i]=data.getInt(p+48);nodeLength[i]=data.getInt(p+52);revisions[i]=data.getLong(p+56);
    }
    /** One header on speed/direction/geometry change, never one upload per attached package. */
    public void replaceTrack(int index,ByteBuffer data) {
        open();if(index<0 || index>=trackCount || layout(data,TRACK_BYTES,1)!=1)throw new IllegalArgumentException("Chain track replacement");
        var v=view(data);int p=v.position();validateTrack(v,p,nodeCount,null,0);
        if(v.getLong(p+56)<=revisions[index] || v.getInt(p+48)!=nodeFirst[index] || v.getInt(p+52)!=nodeLength[index])
            throw new IllegalArgumentException("Chain replacement revision/node namespace");
        upload(tracks,(long)index*TRACK_BYTES,data);remember(index,v,p);
    }
    public void append(ByteBuffer data) {
        open();int n=layout(data,META_BYTES,capacity-count);var v=view(data);var added=new HashSet<Identity>();
        for(int i=0;i<n;i++) {
            int p=v.position()+i*META_BYTES,t=v.getInt(p+16),mask=v.getInt(p+20),flags=v.getInt(p+24);
            var identity=new Identity(v.getLong(p),v.getLong(p+8));
            if(identity.id<=0 || identity.generation<=0 || identities.contains(identity.id,identity.generation) || !added.add(identity)
                    || t<0 || t>=trackCount || (flags&~1)!=0 || v.getInt(p+28)!=0
                    || nodeLength[t]<32 && (mask>>>nodeLength[t])!=0)
                throw new IllegalArgumentException("Chain identity/track/eligibility");
        }
        upload(metadata,(long)count*META_BYTES,data);
        for(int i=0;i<n;i++) {
            int p=v.position()+i*META_BYTES;
            lifecycle[count+i]=(byte)(v.getInt(p+24)&1);candidateTracks[count+i]=v.getInt(p+16);
            candidateIdentities[(count+i)*2]=v.getLong(p);candidateIdentities[(count+i)*2+1]=v.getLong(p+8);
            identities.reserve(v.getLong(p),v.getLong(p+8),count+i);
        }
        count+=n;
    }
    public int nextCandidate(){open();return reusable.isEmpty()?(count<capacity?count:-1):reusable.first();}
    public long captureBarrier(){open();return nextStamp-1;}
    public void makeReusable(int candidate){
        open();if(candidate<0||candidate>=count||lifecycle[candidate]!=2||reusable.contains(candidate))throw new IllegalArgumentException("Chain recycle fence");
        identities.reclaim(candidate);reusable.add(candidate);
        while(count>0&&reusable.remove(count-1)){count--;lifecycle[count]=0;candidateIdentities[count*2]=candidateIdentities[count*2+1]=0;candidateTracks[count]=0;}
    }
    public void write(int candidate,ByteBuffer data){
        open();if(candidate==count){append(data);return;}
        if(!reusable.remove(candidate)||layout(data,META_BYTES,1)!=1)throw new IllegalArgumentException("Chain replacement fence");
        var v=view(data);int p=v.position(),track=v.getInt(p+16),mask=v.getInt(p+20);
        long id=v.getLong(p),generation=v.getLong(p+8);
        if(id<=0||generation<=0||identities.contains(id,generation)||track<0||track>=trackCount||v.getInt(p+24)!=0||v.getInt(p+28)!=0||nodeLength[track]<32&&(mask>>>nodeLength[track])!=0)throw new IllegalArgumentException("Chain replacement identity");
        upload(metadata,(long)candidate*META_BYTES,data);lifecycle[candidate]=0;candidateIdentities[candidate*2]=id;candidateIdentities[candidate*2+1]=generation;candidateTracks[candidate]=track;identities.reserve(id,generation,candidate);
        try(var stack=MemoryStack.stackPush()){upload(events,(long)candidate*EVENT_BYTES,stack.calloc(EVENT_BYTES));upload(flights,(long)candidate*4,stack.calloc(4));}
    }
    /** Final checkpoint may update eligibility only while the exact identity is still prepared.
     * Never reads GL buffers back or changes the channel's candidate/identity namespace. */
    public void rebasePrepared(int candidate,ByteBuffer data) {
        open();
        if(candidate<0 || candidate>=count || lifecycle[candidate]!=0 || layout(data,META_BYTES,1)!=1)
            throw new IllegalArgumentException("Chain checkpoint not prepared");
        var v=view(data);int p=v.position(),track=candidateTracks[candidate],mask=v.getInt(p+20);
        if(v.getLong(p)!=candidateIdentities[candidate*2] || v.getLong(p+8)!=candidateIdentities[candidate*2+1]
                || v.getInt(p+16)!=track || v.getInt(p+24)!=0 || v.getInt(p+28)!=0
                || nodeLength[track]<32 && (mask>>>nodeLength[track])!=0)
            throw new IllegalArgumentException("Chain checkpoint identity/eligibility");
        upload(metadata,(long)candidate*META_BYTES,data);
    }
    public void activate(int candidate) {
        open();if(candidate<0 || candidate>=count || lifecycle[candidate]!=0)throw new IllegalArgumentException("Chain candidate not prepared");
        try(var stack=MemoryStack.stackPush()){upload(metadata,(long)candidate*META_BYTES+24,stack.calloc(4).putInt(0,1));}
        lifecycle[candidate]=1;
    }
    public void retire(int candidate) {
        open();if(candidate<0 || candidate>=count || lifecycle[candidate]==2)throw new IllegalArgumentException("Chain candidate retirement");
        try(var stack=MemoryStack.stackPush()) {
            upload(metadata,(long)candidate*META_BYTES+24,stack.calloc(4));
            upload(events,(long)candidate*EVENT_BYTES,stack.calloc(EVENT_BYTES));upload(flights,(long)candidate*4,stack.calloc(4));
        }
        lifecycle[candidate]=2;
    }
    public void retireIdentity(int candidate,long id,long generation) {
        open();if(candidate<0 || candidate>=count || lifecycle[candidate]!=2)throw new IllegalArgumentException("Chain identity still live");
        identities.retire(id,generation,candidate);
    }
    void validateStep(int bodies){open();if(bodies!=count || nextStep>0xffffffffL)throw new IllegalStateException("Chain body metadata/step namespace");}
    void bindStep(int trackCountLocation) {
        open();GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        try(var stack=MemoryStack.stackPush()){GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,5,stack.ints(tracks,events));}
        GL30.glUniform1ui(trackCountLocation,trackCount);
    }
    void nodes(int bodyBuffer,int chainBuffer) {
        open();if(count==0)return;GL20.glUseProgram(programs[0]);
        try(var stack=MemoryStack.stackPush()){GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,0,stack.ints(metadata,events,chainBuffer,bodyBuffer,tracks,nodes));}
        ui(0,0,count);ui(0,1,trackCount);ui(0,2,nodeCount);ui(0,3,(int)nextStep++);
        GL43.glDispatchCompute((count+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }
    public Capture capture(int maxRecords) {
        open();if(maxRecords<0 || maxRecords>capacity)throw new IllegalArgumentException("Chain capture capacity");
        int bank=0;while(bank<BANKS && captures[bank]!=null)bank++;if(bank==BANKS)return null;
        if(nextStamp>0xffffffffL)throw new IllegalStateException("Chain capture namespace exhausted");
        var capture=new Capture(bank,(int)nextStamp++,headers[bank],records[bank],maxRecords);clear(capture.headerBuffer);
        GL20.glUseProgram(programs[1]);
        try(var stack=MemoryStack.stackPush()){GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,0,stack.ints(events,flights,capture.headerBuffer,capture.recordBuffer));}
        ui(1,0,count);ui(1,4,maxRecords);ui(1,5,capture.stamp);
        if(count>0)GL43.glDispatchCompute((count+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        GL20.glUseProgram(programs[2]);ui(2,4,maxRecords);GL43.glDispatchCompute(1,1,1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        captures[bank]=capture;return capture;
    }
    public void acknowledge(int stamp,ByteBuffer data){apply(stamp,data,false);}
    public void cancel(int stamp,ByteBuffer data){apply(stamp,data,true);}
    /** Entire immutable capture, only AFTER server acceptance of every contained transaction.
     * Also used by the benchmark's mock server; it is never an implicit readback ACK. */
    public void acknowledge(Capture capture) {
        owned(capture);applyRange(capture.stamp,capture.recordBuffer,0,capture.capacity,false,capture.headerBuffer);
    }
    /** Failed staging submission only releases flights; durable node candidates remain pending. */
    public void cancel(Capture capture) {
        owned(capture);applyRange(capture.stamp,capture.recordBuffer,0,capture.capacity,true,capture.headerBuffer);
    }
    /** Immutable GPU journal range for exactly acknowledged network records. */
    public void acknowledgeRange(int stamp,int recordBuffer,int first,int records) {
        applyRange(stamp,recordBuffer,first,records,false,0);
    }
    private void apply(int stamp,ByteBuffer data,boolean cancel) {
        open();int n=layout(data,EVENT_BYTES,capacity);if(stamp==0)throw new IllegalArgumentException("Chain ACK stamp");if(n==0)return;
        upload(ackRecords,0,data);applyRange(stamp,ackRecords,0,n,cancel,0);
    }
    private void applyRange(int stamp,int buffer,int first,int n,boolean cancel,int header) {
        open();if(stamp==0 || buffer<=0 || first<0 || n<0 || (long)first+n>capacity)throw new IllegalArgumentException("Chain ACK range");
        if(n==0)return;GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL20.glUseProgram(programs[3]);
        try(var stack=MemoryStack.stackPush()){GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,0,stack.ints(metadata,events,flights,buffer,header==0?headers[0]:header));}
        ui(3,0,count);ui(3,5,stamp);ui(3,6,n);ui(3,7,cancel?1:0);ui(3,8,header==0?0:1);ui(3,9,first);
        GL43.glDispatchCompute((n+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }
    /** Transport owns immutable records or has copied them into its bounded journal before release. */
    public void finish(Capture capture) {
        owned(capture);
        captures[capture.bank]=null;
    }
    private void owned(Capture capture){open();if(capture==null || capture.bank<0 || capture.bank>=BANKS || captures[capture.bank]!=capture)throw new IllegalArgumentException("Stale chain capture");}
    public int count(){open();return count;}
    public int capacity(){open();return capacity;}
    public int trackCount(){open();return trackCount;}
    public int nodeCount(){open();return nodeCount;}
    public int trackCapacity(){open();return trackCapacity;}
    public int nodeCapacity(){open();return nodeCapacity;}
    public int pendingBuffer(){open();return events;}
    private void ui(int p,int u,int value){GL30.glUniform1ui(locations[p][u],value);}
    private static int layout(ByteBuffer data,int stride,int capacity) {
        if(data==null || !data.isDirect() || data.remaining()%stride!=0 || data.remaining()/stride>capacity)
            throw new IllegalArgumentException("Chain buffer layout");return data.remaining()/stride;
    }
    private static ByteBuffer view(ByteBuffer data){return data.duplicate().order(ByteOrder.nativeOrder());}
    private static void upload(int buffer,long offset,ByteBuffer data) {
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);
        if(data.hasRemaining())GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,offset,data);
    }
    private static int buffer(long bytes) {
        if(bytes>GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE))throw new IllegalArgumentException("Chain SSBO device limit");
        int id=GL15.glGenBuffers();if(id==0)throw new IllegalStateException("Chain buffer allocation failed");
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,bytes,GL15.GL_DYNAMIC_COPY);
        if(GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)!=bytes){GL15.glDeleteBuffers(id);throw new IllegalStateException("Chain storage allocation failed");}return id;
    }
    private static void clear(int buffer) {
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);
        try(var stack=MemoryStack.stackPush()){GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));}
    }
    private static int compile(String source) {
        if(source==null || source.isBlank())throw new IllegalArgumentException("Missing chain shader");
        int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER),program=0;
        try {
            GL20.glShaderSource(shader,"#version 450 core\n"+source);GL20.glCompileShader(shader);
            if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
            program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);GL20.glLinkProgram(program);
            if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(program));return program;
        }catch(RuntimeException failed){if(program!=0)GL20.glDeleteProgram(program);throw failed;}finally{GL20.glDeleteShader(shader);}
    }
    private void open(){if(closed || Thread.currentThread()!=owner)throw new IllegalStateException("Chain track GPU closed/off render thread");}
    @Override public void close() {
        if(closed)return;closed=true;identities.clear();
        for(int program:programs)if(program!=0)GL20.glDeleteProgram(program);
        for(int id:headers)if(id!=0)GL15.glDeleteBuffers(id);for(int id:records)if(id!=0)GL15.glDeleteBuffers(id);
        for(int id:new int[]{tracks,nodes,metadata,events,flights,ackRecords})if(id!=0)GL15.glDeleteBuffers(id);
    }
}
