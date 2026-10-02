package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.function.Function;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Emergency recovery only. Four independent persistent READ mappings retain one completed
 * publication while newer copies are in flight. No population-sized Java copy or decoding in
 * steady state; the native bridge reads an exact identity only when materializing/releasing it.
 * This is deliberately separate from gameplay queries and their <=32KiB staging ring. */
public class PackagePoseCheckpointGpu implements AutoCloseable {
    public enum Transfer { DEVICE_COPY, DIRECT }
    public enum Domain { CHAIN,FREE }
    private static final int BANKS=4;
    private static final class Bank {
        int buffer;ByteBuffer mapped;long fence,submission,submittedNanos;
        PackagePoseQueryGpu.Input input;
        int bodyLimit;
    }
    private final Thread owner=Thread.currentThread();
    private final Bank[] banks=new Bank[BANKS];
    private final int capacity;
    private final long epoch;
    private final Transfer transfer;
    private final Domain domain;
    private int program,countLocation,bodyLocation,capacityLocation,limitLocation,scratch,cursor,latest=-1,pending;
    private long lastSubmission=-1,capturedBytes,skipped,lastLatency;
    private boolean closed;
    /** Transfer choice is exposed for the opt-in same-driver benchmark, not a user protocol. */
    protected PackagePoseCheckpointGpu(int capacity,long epoch,Function<String,String> sources,Transfer transfer,Domain domain) {
        long size=(long)capacity*PackagePoseQueryGpu.RESULT_BYTES;
        if(capacity<1 || capacity>131072 || epoch<=0 || size>GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE)
                || (capacity+63)/64>GL30.glGetIntegeri(GL43.GL_MAX_COMPUTE_WORK_GROUP_COUNT,0))
            throw new IllegalArgumentException("Chain checkpoint capacity/epoch/device limits");
        this.capacity=capacity;this.epoch=epoch;this.transfer=Objects.requireNonNull(transfer);this.domain=Objects.requireNonNull(domain);
        try {
            rebuild(sources);
            int flags=GL30.GL_MAP_READ_BIT|GL44.GL_MAP_PERSISTENT_BIT|GL44.GL_MAP_COHERENT_BIT;
            for(int i=0;i<BANKS;i++) {
                var bank=banks[i]=new Bank();bank.buffer=GL15.glGenBuffers();
                GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,bank.buffer);
                GL44.glBufferStorage(GL31.GL_COPY_WRITE_BUFFER,size,flags);
                bank.mapped=GL30.glMapBufferRange(GL31.GL_COPY_WRITE_BUFFER,0,size,flags);
                if(bank.mapped==null)throw new IllegalStateException("Chain checkpoint persistent mapping failed");
                bank.mapped=bank.mapped.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
            }
            if(transfer==Transfer.DEVICE_COPY) {
                scratch=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,scratch);
                GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,size,GL15.GL_DYNAMIC_COPY);
                if(GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)!=size)
                    throw new IllegalStateException("Chain checkpoint device scratch allocation failed");
            }
        }catch(RuntimeException error){close();throw error;}
    }
    public void rebuild(Function<String,String> sources) {
        open();String source=Objects.requireNonNull(sources).apply("packages/query_checkpoint.comp");
        if(source==null || source.isBlank())throw new IllegalArgumentException("Missing chain checkpoint shader");
        int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER),next=0;
        try {
            GL20.glShaderSource(shader,"#version 450 core\n"+source);GL20.glCompileShader(shader);
            if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
            next=GL20.glCreateProgram();GL20.glAttachShader(next,shader);GL20.glLinkProgram(next);
            if(GL20.glGetProgrami(next,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(next));
            int count=GL20.glGetUniformLocation(next,"uCount"),body=GL20.glGetUniformLocation(next,"uBodyCount"),pool=GL20.glGetUniformLocation(next,"uPoolCapacity");
            int limit=GL20.glGetUniformLocation(next,"uBodyLimit");
            GL41.glProgramUniform1ui(next,GL20.glGetUniformLocation(next,"uCheckpointType"),domain==Domain.CHAIN?1:0);
            if(program!=0)GL20.glDeleteProgram(program);program=next;next=0;
            countLocation=count;bodyLocation=body;capacityLocation=pool;limitLocation=limit;
        }finally {if(next!=0)GL20.glDeleteProgram(next);GL20.glDeleteShader(shader);}
    }
    /** Caller invokes only AFTER a complete particle/admission submission succeeded. A false
     * result leaves its dirty/version token unacknowledged so the next submission can retry. */
    public boolean capture(PackagePoseQueryGpu.Input input,long submission) {
        if(domain!=Domain.CHAIN)throw new IllegalStateException("Free checkpoint requires the published authority body limit");
        return capture(input,input.bodyCount(),submission);
    }
    protected boolean capture(PackagePoseQueryGpu.Input input,int bodyLimit,long submission) {
        open();Objects.requireNonNull(input);
        if(input.count()>capacity || bodyLimit<0 || bodyLimit>input.bodyCount() || bodyLimit>393216
                || domain==Domain.FREE&&bodyLimit>131072 || submission<0 || submission<=lastSubmission)throw new IllegalArgumentException("Package checkpoint publication");
        if(input.count()==0)return false;
        int selected=-1;
        for(int i=0;i<BANKS;i++){int b=(cursor+i)%BANKS;if(b!=latest && banks[b].fence==0){selected=b;break;}}
        if(selected<0){skipped++;return false;}
        var bank=banks[selected];int output=transfer==Transfer.DEVICE_COPY?scratch:bank.buffer;
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        try(var stack=MemoryStack.stackPush()){GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,0,
                stack.ints(input.bodies(),input.chains(),input.metadata(),input.admission(),0,output,input.history()));}
        GL20.glUseProgram(program);GL30.glUniform1ui(countLocation,input.count());
        GL30.glUniform1ui(bodyLocation,input.bodyCount());GL30.glUniform1ui(capacityLocation,input.capacity());
        GL30.glUniform1ui(limitLocation,bodyLimit);
        GL43.glDispatchCompute((input.count()+63)/64,1,1);
        int length=input.count()*PackagePoseQueryGpu.RESULT_BYTES;
        if(transfer==Transfer.DEVICE_COPY) {
            GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,scratch);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,bank.buffer);
            GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,0,0,length);
        }
        // Coherent mapping still requires completion before the CPU can read any record.
        GL42.glMemoryBarrier(GL44.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT);
        long fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
        if(fence==0)throw new IllegalStateException("Chain checkpoint fence allocation failed");
        bank.input=input;bank.bodyLimit=bodyLimit;bank.submission=submission;bank.submittedNanos=System.nanoTime();bank.fence=fence;
        pending++;cursor=(selected+1)%BANKS;lastSubmission=submission;capturedBytes+=length;return true;
    }
    /** Zero timeout and no CPU buffer download. The previous complete bank is never overwritten. */
    public int poll() {
        open();int completed=0;
        for(int i=0;i<BANKS;i++) {
            var bank=banks[i];if(bank.fence==0)continue;
            int status=GL32.glClientWaitSync(bank.fence,GL32.GL_SYNC_FLUSH_COMMANDS_BIT,0);
            if(status==GL32.GL_TIMEOUT_EXPIRED)continue;
            if(status==GL32.GL_WAIT_FAILED)throw new IllegalStateException("Chain checkpoint fence failed; restore Create");
            GL32.glDeleteSync(bank.fence);bank.fence=0;pending--;completed++;
            if(latest<0 || bank.submission>banks[latest].submission){latest=i;lastLatency=System.nanoTime()-bank.submittedNanos;}
        }
        return completed;
    }
    /** No mapped view escapes: reset/close cannot leave a dangling borrowed native pointer. */
    private final java.util.Map<Integer,Long> minimumSubmission=new java.util.HashMap<>();
    public void activateCandidate(int candidate){open();minimumSubmission.put(candidate,lastSubmission+1);}
    public PackagePoseQueryGpu.Result find(int candidate,long id,long generation) {
        open();if(candidate<0 || candidate>=capacity || id<=0 || generation<=0)throw new IllegalArgumentException("Chain checkpoint identity");
        if(latest<0||banks[latest].submission<minimumSubmission.getOrDefault(candidate,0L))return PackagePoseQueryGpu.Result.NONE;
        var bank=banks[latest];if(candidate>=bank.input.count())return PackagePoseQueryGpu.Result.NONE;
        int p=candidate*PackagePoseQueryGpu.RESULT_BYTES;
        if(bank.mapped.getLong(p)!=id || bank.mapped.getLong(p+8)!=generation)return PackagePoseQueryGpu.Result.NONE;
        var result=PackagePoseQueryGpu.decode(bank.mapped,p,bank.input);
        if(result.candidate()!=candidate || result.chain()!=(domain==Domain.CHAIN) || result.body()>=bank.bodyLimit)
            throw new IllegalStateException("Package checkpoint candidate/type/domain mismatch");
        return result;
    }
    public boolean overdue(long now) {
        open();for(var bank:banks)if(bank.fence!=0 && now-bank.submittedNanos>100_000_000L)return true;return false;
    }
    public int pending(){open();return pending;}
    public long epoch(){return epoch;}
    public long latestSubmission(){open();return latest<0?-1:banks[latest].submission;}
    public long readbackBytes(){open();return capturedBytes;}
    public long skipped(){open();return skipped;}
    public long lastLatencyNanos(){open();return lastLatency;}
    public long storageBytes(){return (long)capacity*PackagePoseQueryGpu.RESULT_BYTES*(BANKS+(transfer==Transfer.DEVICE_COPY?1:0));}
    private void open(){if(closed || Thread.currentThread()!=owner)throw new IllegalStateException("Chain checkpoint closed/off owner thread");}
    @Override public void close() {
        if(closed)return;closed=true;
        for(var bank:banks)if(bank!=null){if(bank.fence!=0)GL32.glDeleteSync(bank.fence);if(bank.buffer!=0)GL15.glDeleteBuffers(bank.buffer);bank.mapped=null;}
        if(scratch!=0)GL15.glDeleteBuffers(scratch);if(program!=0)GL20.glDeleteProgram(program);pending=0;latest=-1;
    }
}
