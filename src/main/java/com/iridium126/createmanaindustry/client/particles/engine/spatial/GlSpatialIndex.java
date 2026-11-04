package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames;
import com.iridium126.createmanaindustry.client.gpu.shader.*;
import com.iridium126.createmanaindustry.client.particles.engine.ParticlePrograms;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands;

/** Portable Morton LBVH. The same descriptor and probe directions are used by OptiX. */
public final class GlSpatialIndex implements AutoCloseable {
    private int program;
    private final int capacity,terrainCapacity,n,groups;
    private final int[] keys=new int[2];
    private int nodes,histogram,offsets;
    public GlSpatialIndex(int capacity,int terrainCapacity){
        this(capacity,terrainCapacity,ParticlePrograms.loadParticlePlain("spatial/bvh.comp"));
    }
    public GlSpatialIndex(int capacity,int terrainCapacity,String source){
        this.capacity=capacity;this.terrainCapacity=terrainCapacity;n=capacity+terrainCapacity;groups=(n+255)/256;
        try{program=GpuShaderCompiler.compute("GL spatial LBVH","#version 450 core\n"+ParticlePrograms.commonPrelude()+source);
        keys[0]=buffer(n*8L);keys[1]=buffer(n*8L);nodes=buffer((2L*n-1)*48);histogram=buffer(groups*256L*4);offsets=buffer(groups*256L*4);
        }catch(RuntimeException|LinkageError failure){close();throw failure;}
    }
    private static int buffer(long bytes){int b=GL45.glCreateBuffers();GL45.glNamedBufferData(b,bytes,GL15.GL_DYNAMIC_COPY);if(GL45.glGetNamedBufferParameteri64(b,GL15.GL_BUFFER_SIZE)!=bytes){GpuBufferNames.delete(b);throw new IllegalStateException("GL index allocation failed");}return b;}
    private static void bind(int at,int buffer){GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,at,buffer);}
    private void ui(String name,int n){GL41.glProgramUniform1ui(program,GpuUniforms.loc(program,name),n);}
    private void pair(String name,long n){GL41.glProgramUniform2ui(program,GpuUniforms.loc(program,name),(int)n,(int)(n>>>32));}
    private void phase(int n,int groups){ui("phase",n);GpuReplayCommands.dispatchCompute(Math.max(1,groups),1,1);GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);}
    public void query(int input,int output,long nonce,long epoch,long sourceTick,long scene,int probes,int kind,float x,float y,float z){
        GL20.glUseProgram(program);bind(0,input);bind(1,output);bind(2,keys[0]);bind(3,keys[1]);bind(4,nodes);bind(5,histogram);bind(6,offsets);
        ui("capacity",capacity);ui("terrainCapacity",terrainCapacity);ui("probes",probes);ui("kind",kind);ui("nodeBudget",256);
        pair("nonce",nonce);pair("epoch",epoch);pair("sourceTick",sourceTick);pair("scene",scene);GL41.glProgramUniform3f(program,GpuUniforms.loc(program,"anchor"),x,y,z);
        phase(0,(2*n+254)/256);phase(1,groups);
        int a=0,b=1;for(int pass=0;pass<4;pass++){
            bind(2,keys[a]);bind(3,keys[b]);ui("shift",pass*8);phase(2,groups);phase(3,1);phase(4,groups);int old=a;a=b;b=old;
        }
        bind(2,keys[a]);phase(5,groups);phase(6,groups);phase(7,(capacity+255)/256);phase(8,1);
    }
    @Override public void close(){for(int b:keys)GpuBufferNames.delete(b);for(int b:new int[]{nodes,histogram,offsets})GpuBufferNames.delete(b);GpuPrograms.delete(program);}
}
