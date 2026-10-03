package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.util.function.Function;
import java.util.function.BooleanSupplier;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Render-thread observer sidecar: validates an entire sparse batch before mutation, merges exact
 * wire fields, then predicts/corrects once per instance. There is no simulation authority, world
 * access, CPU pose scan or synchronous readback. Output uses the existing 64-byte body/32-byte
 * history contract; observers do not enter the authority's contact grid. */
public final class PackageObserverGpu implements AutoCloseable {
    public static final int STATE_BYTES=144,PATCH_BYTES=128,BODY_BYTES=64,HISTORY_BYTES=32,CONTROL_BYTES=16;
    public static final int COMPACT_BYTES=80;
    public static final int BASELINE=32,RELEASE=16;
    public static final int UPLOAD_SLOTS=4;
    public static final float SMOOTHING_SECONDS=.05f,PREDICTION_SECONDS=.1f;
    private static final String[] NAMES={"observer_validate","observer_apply","observer_sample","observer_retire"};
    private static final String[] UNIFORMS={"uSlots","uPatches","uNow","uTag","uSmoothing","uPrediction","uCompact","uRetireFence"};
    private final int capacity;
    private final Thread owner=Thread.currentThread();
    private int[] programs;
    private int[][] locations;
    private final int[] uniformSlots=new int[NAMES.length],uniformPatches=new int[NAMES.length];
    private final float[] uniformTime=new float[NAMES.length];
    private final int[] uniformCompact=new int[NAMES.length];
    private int states,control,claims;
    private final int[] patches=new int[UPLOAD_SLOTS];
    private final long[] uploadFences=new long[UPLOAD_SLOTS];
    private final BooleanSupplier consumeCompleted;
    private int nextUpload,boundUpload;
    private final int[] bodies=new int[2],history=new int[2];
    private int slots,tag,published=-1;
    private long version,publishedVersion=-1,publication;
    private float lastTime=-1;
    private boolean closed,compact;
    public PackageObserverGpu(int capacity,Function<String,String> sources) {
        this(capacity,sources,()->true);
    }
    /** Validation gate can delay consumption, but never declares an unfinished fence complete. */
    public PackageObserverGpu(int capacity,Function<String,String> sources,BooleanSupplier consumeCompleted) {
        if(capacity<1 || capacity>131072)throw new IllegalArgumentException("Observer GPU capacity");
        this.capacity=capacity;
        this.consumeCompleted=java.util.Objects.requireNonNull(consumeCompleted);
        if((capacity+63)/64>GL30.glGetIntegeri(GL43.GL_MAX_COMPUTE_WORK_GROUP_COUNT,0)
                || (long)capacity*STATE_BYTES>GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE))
            throw new IllegalArgumentException("Observer GPU device limits");
        try {
            replacePrograms(sources);
            states=buffer((long)capacity*STATE_BYTES);
            for(int i=0;i<UPLOAD_SLOTS;i++)patches[i]=buffer((long)capacity*PATCH_BYTES);
            claims=buffer((long)capacity*4);control=buffer(CONTROL_BYTES);
            for(int i=0;i<2;i++){bodies[i]=buffer((long)capacity*BODY_BYTES);history[i]=buffer((long)capacity*HISTORY_BYTES);}
        }catch(RuntimeException | LinkageError failure){close();throw failure;}
    }
    /** Compile/validate every program before replacing a usable bundle. Existing states survive. */
    public void rebuild(Function<String,String> sources){open();replacePrograms(sources);version++;}
    private void replacePrograms(Function<String,String> sources) {
        int[] next=new int[NAMES.length];int[][] nextLocations=new int[NAMES.length][UNIFORMS.length];
        try {
            for(int i=0;i<NAMES.length;i++) {
                next[i]=compile(sources.apply("packages/"+NAMES[i]+".comp"));
                for(int j=0;j<UNIFORMS.length;j++)nextLocations[i][j]=GL20.glGetUniformLocation(next[i],UNIFORMS[j]);
                GL41.glProgramUniform1f(next[i],nextLocations[i][4],SMOOTHING_SECONDS);
                GL41.glProgramUniform1f(next[i],nextLocations[i][5],PREDICTION_SECONDS);
            }
        }catch(RuntimeException | LinkageError failure){for(int program:next)if(program!=0)GL20.glDeleteProgram(program);throw failure;}
        int[] previous=programs;programs=next;locations=nextLocations;
        java.util.Arrays.fill(uniformSlots,-1);java.util.Arrays.fill(uniformPatches,-1);java.util.Arrays.fill(uniformTime,Float.NaN);
        java.util.Arrays.fill(uniformCompact,-1);
        if(previous!=null)for(int program:previous)GL20.glDeleteProgram(program);
    }
    private static int compile(String source) {
        if(source==null || source.isBlank())throw new IllegalArgumentException("Missing observer shader");
        int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER),program=0;
        try {
            GL20.glShaderSource(shader,"#version 450 core\n"+source);GL20.glCompileShader(shader);
            if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
            program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);GL20.glLinkProgram(program);
            if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(program));return program;
        }catch(RuntimeException failure){if(program!=0)GL20.glDeleteProgram(program);throw failure;}
        finally{GL20.glDeleteShader(shader);}
    }
    private static int buffer(long bytes) {
        int id=GL15.glGenBuffers();if(id==0)throw new IllegalStateException("Observer buffer allocation failed");
        try {
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,bytes,GL15.GL_DYNAMIC_COPY);
            if(GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)!=bytes)
                throw new IllegalStateException("Observer buffer storage allocation failed");
            GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(ByteBuffer)null);return id;
        }catch(RuntimeException failure){GL15.glDeleteBuffers(id);throw failure;}
    }
    /** Caller must first confirm exact retired pool admission and drain older GPU references. */
    public void reclaimSlot(int local){
        open();if(local<0||local>=slots)throw new IllegalArgumentException("Observer recycle domain");
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        clearRange(states,(long)local*STATE_BYTES,STATE_BYTES);clearRange(claims,(long)local*4,4);
        for(int i=0;i<2;i++){clearRange(bodies[i],(long)local*BODY_BYTES,BODY_BYTES);clearRange(history[i],(long)local*HISTORY_BYTES,HISTORY_BYTES);}
        version++;
    }
    private static void clearRange(int buffer,long offset,long bytes){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);try(var stack=MemoryStack.stackPush()){GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,offset,bytes,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(0));}}
    /** Slots are the immutable local namespace's high-water count, never a CPU live census.
     * Unknown/reused identities, duplicate destinations and illegal fields set a sticky GPU error;
     * no member of that or any subsequent batch mutates. The adapter must consume control feedback
     * asynchronously and revoke that stream. Empty uploads submit no workgroups. */
    public void apply(ByteBuffer data,int count,int slots,float time) {
        if(!tryApply(data,count,slots,time))throw new IllegalStateException("Observer uploads busy; retain the batch and retry tryApply");
    }
    /** Returns false without changing slots, publication or any member when every upload slot is
     * still borrowed. The production adapter retains dirty fields and exact lifecycle events;
     * it must not busy-wait, overwrite a slot or treat upload submission as admission. */
    public boolean tryApply(ByteBuffer data,int count,int slots,float time) {
        return tryApply(data,count,slots,time,false);
    }
    /** Sparse pose/release records use a 64-byte mutation command. Identity is resolved on GPU
     * from the immutable full epoch/stream/server-index tuple and validated before any write.
     * Baselines still use tryApply; compact records cannot introduce or reuse a local identity. */
    public boolean tryApplyCompact(ByteBuffer data,int count,int slots,float time) {
        return tryApply(data,count,slots,time,true);
    }
    public void applyCompact(ByteBuffer data,int count,int slots,float time) {
        if(!tryApplyCompact(data,count,slots,time))throw new IllegalStateException("Observer uploads busy; retain compact batch and retry");
    }
    private boolean tryApply(ByteBuffer data,int count,int slots,float time,boolean compact) {
        open();time(time);
        if(time<lastTime)throw new IllegalArgumentException("Observer clock reversed");
        if(count<0 || count>capacity || slots<this.slots || slots>capacity || data==null || !data.isDirect()
                || data.remaining()!=(long)count*(compact?COMPACT_BYTES:PATCH_BYTES))throw new IllegalArgumentException("Observer patch layout/range");
        int bank=count==0?-1:availableUpload();if(count>0 && bank<0)return false;
        if(this.slots!=slots)version++;this.slots=slots;
        if(count==0)return true;
        boundUpload=bank;this.compact=compact;
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,patches[bank]);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,data);
        if(++tag==0){clear(claims);tag=1;}
        bind();use(0,count,time);GL30.glUniform1ui(locations[0][3],tag);dispatch(count);
        use(1,count,time);dispatch(count);GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);version++;
        uploadFences[bank]=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
        if(uploadFences[bank]==0)throw new IllegalStateException("Observer upload fence allocation failed");
        nextUpload=(bank+1)%UPLOAD_SLOTS;return true;
    }
    private int availableUpload() {
        boolean consume=consumeCompleted.getAsBoolean();
        for(int offset=0;offset<UPLOAD_SLOTS;offset++) {
            int bank=(nextUpload+offset)%UPLOAD_SLOTS;long fence=uploadFences[bank];
            if(fence==0)return bank;
            if(!consume)continue;
            int status=GL32.glClientWaitSync(fence,GL32.GL_SYNC_FLUSH_COMMANDS_BIT,0);
            if(status==GL32.GL_WAIT_FAILED)throw new IllegalStateException("Observer upload fence failed");
            if(status==GL32.GL_ALREADY_SIGNALED || status==GL32.GL_CONDITION_SATISFIED){GL32.glDeleteSync(fence);uploadFences[bank]=0;return bank;}
        }
        return -1;
    }
    /** Exact namespace close in one GPU pass; no per-member CPU pose upload or fence wait.
     * Identity stays retired and cannot be reused. Other regional streams keep simulating. */
    public void retireNamespace(long epoch,long stream,float time) {
        open();time(time);
        if(epoch<=0 || stream<=0 || time<lastTime)throw new IllegalArgumentException("Observer retire namespace/time");
        bind();use(3,0,time);GL30.glUniform4ui(locations[3][7],(int)epoch,(int)(epoch>>>32),(int)stream,(int)(stream>>>32));
        dispatch(slots);version++;
    }
    /** Publish only after a sample is fully enqueued. Both history endpoints contain this GPU
     * presentation pose; no ordinary/Iris vertex repeats the network interpolation calculation. */
    public void sample(float time) {
        open();time(time);
        if(time<lastTime)throw new IllegalArgumentException("Observer clock reversed");
        if(published>=0 && publishedVersion==version && time==lastTime)return;
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,control);
        GL43.glClearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,4,8,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(ByteBuffer)null);
        int next=published<0?0:published^1;
        bind();try(MemoryStack stack=MemoryStack.stackPush()) {
            GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,4,stack.ints(bodies[next],history[next]));
        }
        use(2,0,time);dispatch(slots);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        published=next;publishedVersion=version;lastTime=time;publication++;
    }
    private void bind(){try(MemoryStack stack=MemoryStack.stackPush()){
        GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,0,stack.ints(states,patches[boundUpload],control,claims,0,0,patches[boundUpload]));
    }}
    private void use(int program,int count,float time) {
        GL20.glUseProgram(programs[program]);
        if(locations[program][0]>=0 && uniformSlots[program]!=slots){GL30.glUniform1ui(locations[program][0],slots);uniformSlots[program]=slots;}
        if(locations[program][1]>=0 && uniformPatches[program]!=count){GL30.glUniform1ui(locations[program][1],count);uniformPatches[program]=count;}
        if(locations[program][2]>=0 && uniformTime[program]!=time){GL20.glUniform1f(locations[program][2],time);uniformTime[program]=time;}
        int format=compact?1:0;
        if(locations[program][6]>=0 && uniformCompact[program]!=format){GL30.glUniform1ui(locations[program][6],format);uniformCompact[program]=format;}
    }
    private static void dispatch(int count){if(count>0){GL43.glDispatchCompute((count+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);}}
    private static void clear(int buffer){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);
        GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(ByteBuffer)null);}
    private static void time(float time){if(!Float.isFinite(time) || time<0)throw new IllegalArgumentException("Observer clock");}
    public int capacity(){return capacity;}
    public int count(){open();return slots;}
    public int stateBuffer(){open();return states;}
    public int controlBuffer(){open();return control;}
    public int bodyBuffer(){ready();return bodies[published];}
    public int historyBuffer(){ready();return history[published];}
    public long publicationVersion(){ready();return publication;}
    private void open(){if(closed || Thread.currentThread()!=owner)throw new IllegalStateException("Observer GPU closed/off render thread");}
    private void ready(){open();if(published<0 || publishedVersion!=version)throw new IllegalStateException("Observer pose not published after patches");}
    @Override public void close() {
        if(closed)return;closed=true;
        if(programs!=null)for(int program:programs)if(program!=0)GL20.glDeleteProgram(program);
        for(long fence:uploadFences)if(fence!=0)GL32.glDeleteSync(fence);
        for(int buffer:patches)if(buffer!=0)GL15.glDeleteBuffers(buffer);
        for(int buffer:new int[]{states,control,claims,bodies[0],bodies[1],history[0],history[1]})if(buffer!=0)GL15.glDeleteBuffers(buffer);
        programs=null;published=-1;
    }
}
