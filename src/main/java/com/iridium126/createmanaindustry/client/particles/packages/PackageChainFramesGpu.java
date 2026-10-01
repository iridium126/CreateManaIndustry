package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongToIntFunction;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Append-only native track origins and four immutable GPU-expanded frame banks. Parent
 * upload is O(structures), frame composition O(tracks) on the GPU, never O(packages) on Java.
 * Every bank fences both expansion and the subsequent pool import, including failed frames. */
public final class PackageChainFramesGpu implements AutoCloseable {
    public static final int MAX_PARENTS=65,FRAME_BYTES=96,ORIGIN_BYTES=16;
    private static final int MAP_FLAGS=GL30.GL_MAP_WRITE_BIT|GL44.GL_MAP_PERSISTENT_BIT|GL44.GL_MAP_COHERENT_BIT;
    private static final class Bank {int parents,frames,capacity;ByteBuffer mapped;long fence;boolean leased;}
    private final Bank[] banks=new Bank[4];
    private final Thread owner=Thread.currentThread();
    private final LongToIntFunction poll;
    private final int capacity;
    private int origins,program,count,countLocation,parentsLocation;
    private ByteBuffer mappedOrigins;
    private boolean closed;
    private long uploadedBytes,skipped;
    public PackageChainFramesGpu(int capacity,Function<String,String> sources) {
        this(capacity,sources,fence->GL32.glClientWaitSync(fence,GL32.GL_SYNC_FLUSH_COMMANDS_BIT,0));
    }
    public PackageChainFramesGpu(int capacity,Function<String,String> sources,LongToIntFunction poll) {
        if(capacity<1||capacity>131072||(capacity+63)/64>GL30.glGetIntegeri(GL43.GL_MAX_COMPUTE_WORK_GROUP_COUNT,0)
                ||(long)capacity*FRAME_BYTES>GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE))throw new IllegalArgumentException("Chain frame device/capacity");
        this.capacity=capacity;this.poll=Objects.requireNonNull(poll);
        try {
            rebuild(sources);origins=GL15.glGenBuffers();mappedOrigins=map(origins,capacity*ORIGIN_BYTES);
            for(int i=0;i<4;i++) {
                Bank bank=new Bank();banks[i]=bank;bank.parents=GL15.glGenBuffers();bank.mapped=map(bank.parents,MAX_PARENTS*FRAME_BYTES);
                bank.frames=GL15.glGenBuffers();
            }
        }catch(RuntimeException failure){close();throw failure;}
    }
    private static ByteBuffer map(int buffer,int bytes) {
        if(buffer==0)throw new IllegalStateException("Chain frame allocation failed");
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);GL44.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER,bytes,MAP_FLAGS);
        if(GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)!=bytes)throw new IllegalStateException("Chain frame storage unavailable");
        var mapped=GL30.glMapBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,0,bytes,MAP_FLAGS);
        if(mapped==null)throw new IllegalStateException("Chain frame mapping unavailable");return mapped.order(ByteOrder.nativeOrder());
    }
    public void rebuild(Function<String,String> sources) {
        open();int next=compile(sources.apply("packages/chain_frame_prepare.comp"));
        int nextCount=GL20.glGetUniformLocation(next,"uCount"),nextParents=GL20.glGetUniformLocation(next,"uParents");
        if(program!=0)GL20.glDeleteProgram(program);program=next;countLocation=nextCount;parentsLocation=nextParents;
    }
    /** Tail rows are never overwritten; old dispatches consume only their captured count. */
    public void append(int track,int parent,double x,double y,double z) {
        open();if(track!=count||count>=capacity||parent<0||parent>=MAX_PARENTS
                ||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)
                ||Math.abs(x)>200_000_000||Math.abs(y)>200_000_000||Math.abs(z)>200_000_000)throw new IllegalArgumentException("Chain native origin namespace/range");
        int p=count*ORIGIN_BYTES;mappedOrigins.putFloat(p,(float)x).putFloat(p+4,(float)y).putFloat(p+8,(float)z).putInt(p+12,parent);
        count++;uploadedBytes+=ORIGIN_BYTES;
    }
    /** Null means all banks are borrowed; it never waits or overwrites a live source. */
    public View view(ByteBuffer parents,int parentCount) {
        open();if(parents==null||!parents.isDirect()||parentCount<0||parentCount>MAX_PARENTS||parents.remaining()!=parentCount*FRAME_BYTES)
            throw new IllegalArgumentException("Chain parent upload layout");
        Bank chosen=null;
        for(Bank bank:banks) {
            if(bank.fence!=0) {
                int result=poll.applyAsInt(bank.fence);
                if(result==GL32.GL_WAIT_FAILED)throw new IllegalStateException("Chain frame completion failed");
                if(result==GL32.GL_ALREADY_SIGNALED||result==GL32.GL_CONDITION_SATISFIED){GL32.glDeleteSync(bank.fence);bank.fence=0;}
            }
            if(chosen==null&&bank.fence==0&&!bank.leased)chosen=bank;
        }
        if(chosen==null){skipped++;return null;}
        if(chosen.capacity<Math.max(1,count)) {
            chosen.capacity=Math.min(capacity,Math.max(64,Integer.highestOneBit(Math.max(1,count)-1)<<1));
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,chosen.frames);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,(long)chosen.capacity*FRAME_BYTES,GL15.GL_DYNAMIC_DRAW);
            if(GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)!=(long)chosen.capacity*FRAME_BYTES)
                throw new IllegalStateException("Chain expanded frame storage unavailable");
        }
        chosen.mapped.clear();chosen.mapped.put(parents.duplicate());uploadedBytes+=parents.remaining();
        GL42.glMemoryBarrier(GL44.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        try(var stack=MemoryStack.stackPush()){GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,0,stack.ints(chosen.parents,origins,chosen.frames));}
        GL20.glUseProgram(program);GL30.glUniform1ui(countLocation,count);GL30.glUniform1ui(parentsLocation,parentCount);
        if(count>0){GL43.glDispatchCompute((count+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);}
        chosen.leased=true;return new View(chosen,count);
    }
    public final class View implements AutoCloseable {
        private final Bank bank;private final int count;private boolean ended;
        private View(Bank bank,int count){this.bank=bank;this.count=count;}
        public int buffer(){open();if(ended)throw new IllegalStateException("Chain frame view ended");return bank.frames;}
        public int count(){open();if(ended)throw new IllegalStateException("Chain frame view ended");return count;}
        @Override public void close() {
            open();if(ended)return;
            long fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
            if(fence==0)throw new IllegalStateException("Chain frame fence unavailable");
            bank.fence=fence;bank.leased=false;ended=true;
        }
    }
    public int count(){open();return count;}public long uploadedBytes(){open();return uploadedBytes;}public long skipped(){open();return skipped;}
    private void open(){if(closed||Thread.currentThread()!=owner)throw new IllegalStateException("Chain frames closed/off render thread");}
    private static int compile(String source) {
        if(source==null||source.isBlank())throw new IllegalArgumentException("Missing chain frame shader");
        int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER),next=0;
        try {
            GL20.glShaderSource(shader,"#version 450 core\n"+source);GL20.glCompileShader(shader);
            if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
            next=GL20.glCreateProgram();GL20.glAttachShader(next,shader);GL20.glLinkProgram(next);
            if(GL20.glGetProgrami(next,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(next));return next;
        }catch(RuntimeException failure){if(next!=0)GL20.glDeleteProgram(next);throw failure;}
        finally{GL20.glDeleteShader(shader);}
    }
    @Override public void close() {
        if(closed)return;open();closed=true;
        for(Bank bank:banks)if(bank!=null){if(bank.fence!=0)GL32.glDeleteSync(bank.fence);if(bank.parents!=0)GL15.glDeleteBuffers(bank.parents);if(bank.frames!=0)GL15.glDeleteBuffers(bank.frames);}
        if(origins!=0)GL15.glDeleteBuffers(origins);if(program!=0)GL20.glDeleteProgram(program);
    }
}
