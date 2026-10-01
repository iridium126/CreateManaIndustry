package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/**
 * Render-thread free-package dirty detection against SERVER-ACKNOWLEDGED integer baselines.
 * Four immutable output banks. No CPU body scans, blocking reads or implicit ACKs.
 * Internal component; production ownership still requires collision/admission/observer wiring.
 */
public final class PackageDeltaGpu implements AutoCloseable {
    public static final int META_BYTES=32,BASELINE_BYTES=32,RECORD_BYTES=64,PREDICTOR_BYTES=16,BANKS=4,MAX_ACK_RECORDS=16384;
    public record Capture(int bank,int stamp,int headerBuffer,int recordBuffer,int capacity) {}
    private static final String[] NAMES={"delta_detect","delta_finalize","delta_ack"};
    private static final String[] UNIFORMS={"uCount","uStamp","uCapacity","uBodyCount","uOriginOffset","uRecords","uCancel","uRecordOffset","uRelativePosition","uPredictedPosition"};
    private final int[] programs=new int[3],headers=new int[BANKS],records=new int[BANKS];
    private final int[][] locations=new int[3][UNIFORMS.length];
    private final Capture[] captures=new Capture[BANKS];
    private final int capacity;
    private final boolean relativePositions;
    private final boolean predictedPositions;
    // Candidates leave this set permanently on activation or matching terminal notice.
    // This prevents a late ACTIVE from resurrecting a released acquisition.
    private record Prepared(long id,long generation,int localId) {}
    private final Map<Integer,Prepared> preparing=new HashMap<>();
    private int metadata,baselines,flights,ackRecords,predictors,count;
    private record Identity(long id,long generation) {}
    private final PackageGpuIdentityReservations identities=new PackageGpuIdentityReservations();
    private Set<Integer> bodyIndices=new HashSet<>();
    private long nextStamp=1;
    private boolean closed;

    public PackageDeltaGpu(int capacity,Function<String,String> sources) {
        this(capacity,sources,false);
    }
    /** Immutable encoding mode for this epoch; exact ACK reconstructs the locked baseline. */
    public PackageDeltaGpu(int capacity,Function<String,String> sources,boolean relativePositions) {
        this(capacity,sources,relativePositions,false);
    }
    /** Predict wire POSITION using the last exactly acknowledged displacement. Requires relative
     * batch encoding; publication timing/physics/quantization and raw record ABI are unchanged. */
    public PackageDeltaGpu(int capacity,Function<String,String> sources,boolean relativePositions,boolean predictedPositions) {
        if(capacity<=0 || capacity>131072 || (capacity+63)/64>GL30.glGetIntegeri(GL43.GL_MAX_COMPUTE_WORK_GROUP_COUNT,0)
                || (long)capacity*RECORD_BYTES>GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE))
            throw new IllegalArgumentException("Package delta capacity/device limits");
        if(predictedPositions && !relativePositions)throw new IllegalArgumentException("Predicted package positions require relative encoding");
        this.capacity=capacity;this.relativePositions=relativePositions;this.predictedPositions=predictedPositions;
        try {
            rebuild(sources);
            metadata=buffer((long)capacity*META_BYTES);baselines=buffer((long)capacity*BASELINE_BYTES);
            flights=buffer((long)capacity*4);ackRecords=buffer((long)MAX_ACK_RECORDS*RECORD_BYTES);
            if(predictedPositions){predictors=buffer((long)capacity*PREDICTOR_BYTES);clear(predictors);}
            for(int i=0;i<BANKS;i++){headers[i]=buffer(16);records[i]=buffer((long)capacity*RECORD_BYTES);}
            clear(flights);
        }catch(RuntimeException failure){close();throw failure;}
    }
    /** Validate ALL replacement kernels before publishing any program. Buffers/flights survive. */
    public void rebuild(Function<String,String> sources) {
        open();int[] replacements=new int[3];int[][] nextLocations=new int[3][UNIFORMS.length];
        try {
            for(int i=0;i<3;i++) {
                replacements[i]=compile(sources.apply("packages/"+NAMES[i]+".comp"));
                for(int j=0;j<UNIFORMS.length;j++)nextLocations[i][j]=GL20.glGetUniformLocation(replacements[i],UNIFORMS[j]);
                GL41.glProgramUniform1ui(replacements[i],nextLocations[i][8],relativePositions?1:0);
                GL41.glProgramUniform1ui(replacements[i],nextLocations[i][9],predictedPositions?1:0);
            }
        }catch(RuntimeException failure){for(int program:replacements)if(program!=0)GL20.glDeleteProgram(program);throw failure;}
        for(int i=0;i<3;i++){if(programs[i]!=0)GL20.glDeleteProgram(programs[i]);programs[i]=replacements[i];locations[i]=nextLocations[i];}
    }
    /** Epoch initialization only. Candidate indices cannot be reused within an epoch. */
    public void upload(ByteBuffer meta,ByteBuffer baseline,int count) {
        open();if(count<0 || count>capacity || !meta.isDirect() || !baseline.isDirect()
                || meta.remaining()!=count*META_BYTES || baseline.remaining()!=count*BASELINE_BYTES)
            throw new IllegalArgumentException("Package delta initialization layout");
        for(Capture capture:captures)if(capture!=null)throw new IllegalStateException("Release immutable captures before epoch initialization");
        var view=meta.duplicate().order(ByteOrder.nativeOrder());
        Set<Identity> nextIdentities=new HashSet<>();Set<Integer> nextBodies=new HashSet<>();
        validateMetadata(view,count,nextIdentities,nextBodies);
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,metadata);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,meta);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,baselines);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,baseline);
        clear(flights);if(predictors!=0)clear(predictors);this.count=count;
        identities.clear();reserveIdentities(meta,count,0);bodyIndices=nextBodies;
        preparing.clear();trackPrepared(meta,0,count);
    }
    private static void validateMetadata(ByteBuffer view,int count,Set<Identity> identities,Set<Integer> bodies) {
        for(int i=0;i<count;i++) {
            int p=view.position()+i*META_BYTES;
            long id=view.getLong(p),generation=view.getLong(p+8);
            int body=view.getInt(p+16);
            if(id<=0 || generation<=0 || body<0 || view.getInt(p+20)<0
                    || !identities.add(new Identity(id,generation)) || !bodies.add(body)
                    || (view.getInt(p+24)&~1)!=0)throw new IllegalArgumentException("Package delta identity/index/flags");
        }
    }
    /** Extend an established epoch without resetting other candidates' flights or baselines. */
    public void append(ByteBuffer meta,ByteBuffer baseline,int added) {
        open();if(added<0 || added>capacity-count || !meta.isDirect() || !baseline.isDirect()
                || meta.remaining()!=added*META_BYTES || baseline.remaining()!=added*BASELINE_BYTES)
            throw new IllegalArgumentException("Package delta append layout");
        Set<Identity> nextIdentities=new HashSet<>();Set<Integer> nextBodies=new HashSet<>();
        validateMetadata(meta.duplicate().order(ByteOrder.nativeOrder()),added,nextIdentities,nextBodies);
        for(Identity identity:nextIdentities)if(identities.contains(identity.id,identity.generation))
            throw new IllegalArgumentException("Duplicate existing package delta identity");
        for(int body:nextBodies)if(bodyIndices.contains(body))
            throw new IllegalArgumentException("Duplicate existing package delta body");
        if(added==0)return;
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,metadata);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,(long)count*META_BYTES,meta);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,baselines);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,(long)count*BASELINE_BYTES,baseline);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,flights);
        try(var stack=MemoryStack.stackPush()){GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,(long)count*4,(long)added*4,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));}
        if(predictors!=0) {
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,predictors);
            try(var stack=MemoryStack.stackPush()){GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,(long)count*PREDICTOR_BYTES,(long)added*PREDICTOR_BYTES,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));}
        }
        trackPrepared(meta,count,added);
        reserveIdentities(meta,added,count);count+=added;bodyIndices.addAll(nextBodies);
    }
    private void reserveIdentities(ByteBuffer data,int length,int first) {
        var v=data.duplicate().order(ByteOrder.nativeOrder());
        for(int i=0;i<length;i++){int p=v.position()+i*META_BYTES;identities.reserve(v.getLong(p),v.getLong(p+8),first+i);}
    }
    /** Retired GPU body/candidate stay immutable for any older in-flight delta and ACK. */
    public void retireIdentity(int candidate,long id,long generation) {
        open();if(candidate<0 || candidate>=count)throw new IllegalArgumentException("Package delta identity retirement");
        identities.retire(id,generation,candidate);preparing.remove(candidate);
    }
    private void trackPrepared(ByteBuffer meta,int first,int length) {
        ByteBuffer view=meta.duplicate().order(ByteOrder.nativeOrder());
        for(int i=0;i<length;i++) {
            int p=view.position()+i*META_BYTES;
            if((view.getInt(p+24)&1)==0)
                preparing.put(first+i,new Prepared(view.getLong(p),view.getLong(p+8),view.getInt(p+20)));
        }
    }
    /** Final acquisition checkpoint only. Inactive candidates have no flights;
     * unrelated immutable captures and acknowledged baselines remain untouched. */
    public void rebasePrepared(int candidate,ByteBuffer baseline) {
        prepared(candidate);
        if(!baseline.isDirect() || baseline.remaining()!=BASELINE_BYTES)
            throw new IllegalArgumentException("Package final baseline layout");
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,baselines);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,(long)candidate*BASELINE_BYTES,baseline);
        if(predictors!=0) {
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,predictors);
            try(var stack=MemoryStack.stackPush()){GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,(long)candidate*PREDICTOR_BYTES,PREDICTOR_BYTES,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));}
        }
    }
    /** Call only after the matching server ACTIVE and committed admission are confirmed. */
    public void activate(int candidate) {
        prepared(candidate);
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,metadata);
        try(var stack=MemoryStack.stackPush()) {
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,(long)candidate*META_BYTES+24,stack.ints(1));
        }
        preparing.remove(candidate);
    }
    private void prepared(int candidate) {
        open();
        if(candidate<0 || candidate>=count || !preparing.containsKey(candidate))
            throw new IllegalArgumentException("Package candidate is not awaiting activation");
    }
    public int metadataCount(){open();return count;}
    public int capacity(){open();return capacity;}
    public boolean relativePositions(){open();return relativePositions;}
    public boolean predictedPositions(){open();return predictedPositions;}
    /** Null means all output banks are still borrowed. Simulation continues; dirty state is retained. */
    public Capture capture(int bodyBuffer,int bodyCount,float ox,float oy,float oz,int outputCapacity) {
        open();if(bodyBuffer<=0 || bodyCount<0 || outputCapacity<0 || outputCapacity>capacity
                || !Float.isFinite(ox) || !Float.isFinite(oy) || !Float.isFinite(oz))throw new IllegalArgumentException("Package delta source/offset/capacity");
        int bank=0;while(bank<BANKS && captures[bank]!=null)bank++;if(bank==BANKS)return null;
        if(nextStamp>0xffffffffL)throw new IllegalStateException("Package delta stamp exhausted; replace epoch resources");
        var capture=new Capture(bank,(int)nextStamp++,headers[bank],records[bank],outputCapacity);captures[bank]=capture;
        clear(headers[bank]);
        bind(0,capture,bodyBuffer);ui(0,3,bodyCount);GL20.glUniform3f(locations[0][4],ox,oy,oz);
        if(count>0)GL43.glDispatchCompute((count+63)/64,1,1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        bind(1,capture,bodyBuffer);GL43.glDispatchCompute(1,1,1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        return capture;
    }
    /** Exact server ACK subset, copied from the matching capture journal, never from current bodies. */
    public void acknowledge(int stamp,ByteBuffer acknowledged) {
        applyRecords(stamp,acknowledged,0);
    }
    /** Server-owned terminal identity notifications override an outstanding normal delta flight. */
    public void serverReleased(ByteBuffer identities){
        applyRecords(0,identities,2);
        ByteBuffer view=identities.duplicate().order(ByteOrder.nativeOrder());
        for(int p=view.position();p<view.limit();p+=RECORD_BYTES) {
            int candidate=view.getInt(p+16);Prepared expected=preparing.get(candidate);
            if(expected!=null && view.getInt(p+28)==1 && view.getLong(p)==expected.id
                    && view.getLong(p+8)==expected.generation && view.getInt(p+20)==expected.localId)
                preparing.remove(candidate);
        }
    }
    private void applyRecords(int stamp,ByteBuffer acknowledged,int mode) {
        open();int bytes=acknowledged.remaining();
        if(stamp==0 && mode!=2 || !acknowledged.isDirect() || bytes%RECORD_BYTES!=0 || bytes/RECORD_BYTES>MAX_ACK_RECORDS)
            throw new IllegalArgumentException("Package delta ACK layout/stamp");
        int n=bytes/RECORD_BYTES;if(n==0)return;
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,ackRecords);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,acknowledged);
        acknowledgeRange(stamp,ackRecords,0,n,mode);
    }
    /** Immutable GPU journal range. Avoid uploading raw records back to their originating GPU. */
    public void acknowledgeRange(int stamp,int source,int firstRecord,int n){acknowledgeRange(stamp,source,firstRecord,n,0);}
    private void acknowledgeRange(int stamp,int source,int firstRecord,int n,int mode) {
        open();if(source<=0 || firstRecord<0 || n<0 || n>MAX_ACK_RECORDS || (long)firstRecord+n>131072 || stamp==0 && mode!=2)
            throw new IllegalArgumentException("Package GPU journal ACK range");
        if(n==0)return;
        var input=new Capture(-1,stamp,headers[0],source,n);bind(2,input,0);ui(2,5,n);ui(2,6,mode);ui(2,7,firstRecord);
        GL43.glDispatchCompute((n+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }
    /** Transport failure: release flights without advancing any baseline. Events will be emitted again. */
    public void cancel(Capture capture) {
        owned(capture);bind(2,capture,0);ui(2,5,-1);ui(2,6,1);ui(2,7,0);
        if(capture.capacity>0)GL43.glDispatchCompute((capture.capacity+63)/64,1,1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);finish(capture);
    }
    /** Caller must have enqueued all GPU copies, or copied records into its bounded ACK journal first. */
    public void finish(Capture capture){owned(capture);captures[capture.bank]=null;}
    private void owned(Capture capture){open();if(capture==null || capture.bank<0 || capture.bank>=BANKS || captures[capture.bank]!=capture)throw new IllegalArgumentException("Stale package capture");}
    private void bind(int program,Capture capture,int bodyBuffer) {
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        GL20.glUseProgram(programs[program]);
        try(var stack=MemoryStack.stackPush()) {
            GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,0,stack.ints(metadata,baselines,flights,capture.recordBuffer,capture.headerBuffer,bodyBuffer,predictors));
        }
        ui(program,0,count);ui(program,1,capture.stamp);ui(program,2,capture.capacity);
    }
    private void ui(int p,int index,int value){GL30.glUniform1ui(locations[p][index],value);}
    private static int buffer(long bytes){int id=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,bytes,GL15.GL_DYNAMIC_DRAW);return id;}
    private static void clear(int buffer) {
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);
        try(var stack=MemoryStack.stackPush()){GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));}
    }
    private static int compile(String source) {
        if(source==null || source.isBlank())throw new IllegalArgumentException("Missing package delta shader");
        int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER),program=0;
        try {
            GL20.glShaderSource(shader,"#version 450 core\n"+source);GL20.glCompileShader(shader);
            if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
            program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);GL20.glLinkProgram(program);
            if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(program));return program;
        }catch(RuntimeException failed){if(program!=0)GL20.glDeleteProgram(program);throw failed;}
        finally{GL20.glDeleteShader(shader);}
    }
    private void open(){if(closed)throw new IllegalStateException("Package delta GPU closed");}
    @Override public void close() {
        if(closed)return;closed=true;
        preparing.clear();
        for(int program:programs)if(program!=0)GL20.glDeleteProgram(program);
        for(int id:headers)if(id!=0)GL15.glDeleteBuffers(id);for(int id:records)if(id!=0)GL15.glDeleteBuffers(id);
        for(int id:new int[]{metadata,baselines,flights,ackRecords,predictors})if(id!=0)GL15.glDeleteBuffers(id);
    }
}
