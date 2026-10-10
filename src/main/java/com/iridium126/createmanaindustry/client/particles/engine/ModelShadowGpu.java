package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.particles.packages.PackageDrawCulling;
import java.util.function.Function;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Shadow-only permutations/commands. Reads the committed pool, never modifies main draw data. */
public final class ModelShadowGpu implements AutoCloseable {
    private final int capacity,meta;private int instances,commands;private final int[] programs=new int[3];
    private final int[][] locations=new int[3][14];private boolean closed;
    private static final String[] NAMES={"uCapacity","uCarrierBase","uNativeSize","uEntityViewScale","uCamera","uFrustum","uFrustumCount","uCullBounds","uTimeSec","uWave","uWaveTarget","uWaveTier","uMetaSlot","uCutout"};
    public ModelShadowGpu(int capacity,Function<String,String> source){
        if(capacity<1)throw new IllegalArgumentException("Model shadow capacity");this.capacity=capacity;meta=capacity+ParticleBuffers.CARRIER_CAP;
        try{
            instances=allocate((long)(meta+1)*8);commands=allocate((ParticleBuffers.INDIRECT_UINTS+4L)*4);
            for(int p=0;p<3;p++){
                programs[p]=compile(ParticlePrograms.commonPrelude()+source.apply("model_shadow_"+new String[]{"prepare","collect","finish"}[p]+".comp"));
                for(int i=0;i<NAMES.length;i++)locations[p][i]=GL20.glGetUniformLocation(programs[p],NAMES[i]);
                GL41.glProgramUniform1ui(programs[p],locations[p][0],capacity);GL41.glProgramUniform1ui(programs[p],locations[p][1],capacity);GL41.glProgramUniform1ui(programs[p],locations[p][12],meta);
            }
            int finish=programs[2];GL41.glProgramUniform1ui(finish,GL20.glGetUniformLocation(finish,"uCutout"),AllayModelGeometry.BODY_OPAQUE_INDEX_COUNT);
            GL41.glProgramUniform1ui(finish,GL20.glGetUniformLocation(finish,"uOpaque"),AllayModelGeometry.OPAQUE_INDEX_COUNT);
            GL41.glProgramUniform1ui(finish,GL20.glGetUniformLocation(finish,"uTotal"),AllayModelGeometry.INDICES.length);
        }catch(RuntimeException error){close();throw error;}
    }
    private static int allocate(long bytes){if(bytes>GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE))throw new IllegalArgumentException("Shadow buffer device limit");
        int id=GL15.glGenBuffers();try{GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,bytes,GL15.GL_DYNAMIC_COPY);
            if(GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)!=bytes)throw new IllegalStateException("Shadow storage allocation");return id;
        }catch(RuntimeException error){GL15.glDeleteBuffers(id);throw error;}}
    private static int compile(String source){int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER),program=0;try{
        GL20.glShaderSource(shader,"#version 450 core\n"+source);GL20.glCompileShader(shader);if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
        program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);GL20.glLinkProgram(program);if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(program));return program;
        }catch(RuntimeException error){if(program!=0)GL20.glDeleteProgram(program);throw error;}finally{GL20.glDeleteShader(shader);}}
    public void prepare(int pool,int emitters,int counter,PackageDrawCulling culling,float x,float y,float z,float width,float height,float viewScale,float time,float[] waves,float[] targets,float[] tiers){
        if(closed)throw new IllegalStateException("Model shadows closed");if(culling.count<0||culling.count>13)throw new IllegalArgumentException("Shadow planes");
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT|GL42.GL_COMMAND_BARRIER_BIT);
        try(var stack=MemoryStack.stackPush()){GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,0,stack.ints(pool,emitters,counter,instances,commands));}
        GL20.glUseProgram(programs[0]);GL43.glDispatchCompute(1,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_COMMAND_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER,commands);GL20.glUseProgram(programs[1]);int[] l=locations[1];
        GL20.glUniform2f(l[2],width,height);GL20.glUniform1f(l[3],viewScale);GL20.glUniform3f(l[4],x,y,z);GL20.glUniform4fv(l[5],culling.planes);GL30.glUniform1ui(l[6],culling.count);
        GL20.glUniform2f(l[7],culling.distance,culling.safe);GL20.glUniform1f(l[8],time);GL20.glUniform4fv(l[9],waves);GL20.glUniform4fv(l[10],targets);GL20.glUniform1fv(l[11],tiers);
        GL43.glDispatchComputeIndirect(ParticleBuffers.INDIRECT_UINTS*4L);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        GL20.glUseProgram(programs[2]);GL43.glDispatchCompute(1,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT|GL42.GL_COMMAND_BARRIER_BIT);
    }
    public int instances(){return instances;}public int commands(){return commands;}public int capacity(){return capacity;}
    public long bytes(){return (long)(meta+1)*8+(ParticleBuffers.INDIRECT_UINTS+4L)*4;}
    @Override public void close(){if(closed)return;closed=true;for(int p:programs)if(p!=0)GL20.glDeleteProgram(p);if(instances!=0)GL15.glDeleteBuffers(instances);if(commands!=0)GL15.glDeleteBuffers(commands);}
}
