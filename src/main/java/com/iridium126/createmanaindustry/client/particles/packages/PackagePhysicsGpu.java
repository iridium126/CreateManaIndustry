package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.util.function.Function;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/**
 * Render-thread package solver. Buffers are sidecar state, not another particle pool.
 * Caller owns GL state boundaries and the common particle-slot mapping. There are no readbacks here.
 * Production takeover must not be enabled until coverage, swept contacts and lifecycle adapters exist.
 */
public final class PackagePhysicsGpu implements AutoCloseable {
    public static final int BODY_BYTES=64, CHAIN_BYTES=64, ITERATIONS=4;
    private static final String[] NAMES={"predict","grid","solve","chain","history"};
    private final int[] programs=new int[5], states=new int[2];
    private final int[][] locations=new int[5][8];
    private static final String[] UNIFORMS={"uCount","uTableMask","uCellSize","uDt","uGravity","uDrag","uFriction","uChain"};
    private int heads, links, chains, history, count, current;
    private final int capacity, tableSize;
    private final float cellSize;
    private boolean closed;

    public PackagePhysicsGpu(int capacity, float cellSize, Function<String,String> sources) {
        if(capacity<=0 || capacity>1_048_576 || !(cellSize>0) || !Float.isFinite(cellSize))
            throw new IllegalArgumentException("Invalid package physics capacity/cell size");
        this.capacity=capacity;this.cellSize=cellSize;
        tableSize=Integer.highestOneBit(Math.max(64,capacity-1))<<1;
        if((capacity+63)/64>GL30.glGetIntegeri(GL43.GL_MAX_COMPUTE_WORK_GROUP_COUNT,0)
                || (long)capacity*BODY_BYTES>GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE))
            throw new IllegalArgumentException("Package buffers exceed device limits");
        try {
            for(int i=0;i<programs.length;i++) {
                programs[i]=compile(sources.apply("packages/"+NAMES[i]+".comp"));
                for(int j=0;j<UNIFORMS.length;j++)locations[i][j]=GL20.glGetUniformLocation(programs[i],UNIFORMS[j]);
                GL41.glProgramUniform1ui(programs[i],locations[i][1],tableSize-1);
                GL41.glProgramUniform1f(programs[i],locations[i][2],cellSize);
            }
            states[0]=buffer((long)capacity*BODY_BYTES);states[1]=buffer((long)capacity*BODY_BYTES);
            heads=buffer((long)tableSize*4);links=buffer((long)capacity*4);chains=buffer((long)capacity*CHAIN_BYTES);
            history=buffer((long)capacity*32);
        } catch(RuntimeException failure) { close();throw failure; }
    }
    private static int buffer(long bytes) {
        int id=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,bytes,GL15.GL_DYNAMIC_DRAW);return id;
    }
    private static int compile(String source) {
        if(source==null || source.isBlank())throw new IllegalArgumentException("Missing package shader");
        int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER), program=0;
        try {
            GL20.glShaderSource(shader,"#version 450 core\n"+source);GL20.glCompileShader(shader);
            if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)
                throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
            program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);GL20.glLinkProgram(program);
            if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)
                throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
            return program;
        } catch(RuntimeException failure) { if(program!=0)GL20.glDeleteProgram(program);throw failure; }
        finally { GL20.glDeleteShader(shader); }
    }
    public void upload(ByteBuffer bodies,int count) {
        ensureOpen();
        if(count<0 || count>capacity || bodies.remaining()!=count*BODY_BYTES || !bodies.isDirect())
            throw new IllegalArgumentException("Invalid package body upload");
        ByteBuffer view=bodies.duplicate().order(java.nio.ByteOrder.nativeOrder());
        for(int i=0;i<count;i++) {
            int p=view.position()+i*BODY_BYTES;
            for(int j=0;j<16;j++)if(!Float.isFinite(view.getFloat(p+j*4)))
                throw new IllegalArgumentException("Non-finite body");
            for(int j=0;j<3;j++) {
                float extent=view.getFloat(p+32+j*4);
                if(!(extent>0 && extent<=cellSize*.5f))throw new IllegalArgumentException("Collider needs subdivision");
                if(Math.abs(view.getFloat(p+j*4)/cellSize)>100_000_000)
                    throw new IllegalArgumentException("Body requires a local region origin");
            }
            if(view.getFloat(p+12)<0)throw new IllegalArgumentException("Negative inverse mass");
        }
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,states[current]);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,bodies);this.count=count;
        captureHistory(0);
    }
    public void uploadChains(ByteBuffer data) {
        ensureOpen();
        if(!data.isDirect() || data.remaining()!=count*CHAIN_BYTES)throw new IllegalArgumentException("Chain layout");
        ByteBuffer view=data.duplicate().order(java.nio.ByteOrder.nativeOrder());
        for(int i=0;i<count;i++) {
            int p=view.position()+i*CHAIN_BYTES;
            for(int j=0;j<16;j++)if(!Float.isFinite(view.getFloat(p+j*4)))
                throw new IllegalArgumentException("Non-finite chain");
            if(view.getFloat(p+12)<0 || view.getFloat(p+28)<0)
                throw new IllegalArgumentException("Negative chain radius/length");
        }
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,chains);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,data);
        captureHistory(2);
    }
    /** Swept static collision plus Jacobi dynamic contacts. A negative sleep field requests CPU fallback. */
    public void step(float dt) {
        ensureStep(dt);if(count==0)return;
        captureHistory(0);
        clearHeads();bind(1);dispatch();
        bind(0);f(0,3,dt);f(0,4,32f);
        f(0,5,(float)Math.pow(.98,dt*20));dispatch();current^=1;
        for(int iteration=0;iteration<ITERATIONS;iteration++) {
            clearHeads();bind(1);dispatch();
            bind(2);f(2,6,(float)Math.pow(.6,dt*20/ITERATIONS));
            dispatch();current^=1;
        }
    }
    public void stepChains(float dt) {
        ensureStep(dt);if(count==0)return;
        captureHistory(1);
        bind(3);f(3,3,dt);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,4,chains);
        dispatch();current^=1;
    }
    private void ensureOpen(){if(closed)throw new IllegalStateException("Package solver closed");}
    private void captureHistory(int chainMode) {
        if(count==0)return;
        bind(4);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,4,chains);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,5,history);
        GL20.glUniform1i(locations[4][7],chainMode);dispatch();
    }
    private void ensureStep(float dt){ensureOpen();if(!(dt>0 && dt<=.05f))throw new IllegalArgumentException("Physics substep");}
    private void bind(int index) {
        GL20.glUseProgram(programs[index]);
        try(MemoryStack stack=MemoryStack.stackPush()) {
            GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,0,stack.ints(states[current],states[current^1],heads,links));
        }
        GL30.glUniform1ui(locations[index][0],count);
    }
    private void f(int index,int uniform,float value){GL20.glUniform1f(locations[index][uniform],value);}
    private void clearHeads() {
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,heads);
        try(MemoryStack stack=MemoryStack.stackPush()) {
            GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,stack.ints(-1));
        }
    }
    private void dispatch(){GL43.glDispatchCompute((count+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);}
    public int stateBuffer(){ensureOpen();return states[current];}
    public int chainBuffer(){ensureOpen();return chains;}
    public int historyBuffer(){ensureOpen();return history;}
    public int count(){return count;}
    @Override public void close() {
        if(closed)return;closed=true;
        for(int p:programs)if(p!=0)GL20.glDeleteProgram(p);
        for(int b:states)if(b!=0)GL15.glDeleteBuffers(b);
        if(heads!=0)GL15.glDeleteBuffers(heads);if(links!=0)GL15.glDeleteBuffers(links);if(chains!=0)GL15.glDeleteBuffers(chains);
        if(history!=0)GL15.glDeleteBuffers(history);
    }
}
