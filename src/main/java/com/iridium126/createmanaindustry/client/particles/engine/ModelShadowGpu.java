package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.gpu.GpuCapabilities;

import com.iridium126.createmanaindustry.client.packages.render.PackageDrawCulling;
import java.util.function.Function;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Shadow-only permutations/commands. Reads the committed pool, never modifies main draw data. */
public final class ModelShadowGpu implements AutoCloseable {
    private final int capacity,meta,carrierCapacity;private int instances,commands;private final int[] programs=new int[3];
    private final int[][] locations=new int[3][14];private boolean closed;
    private final int[][] populationLocations=new int[3][2];
    private static final String[] NAMES={"uCapacity","uCarrierBase","uNativeSize","uEntityViewScale","uCamera","uFrustum","uFrustumCount","uCullBounds","uTimeSec","uWave","uWaveTarget","uWaveTier","uMetaSlot","uCutout"};
    public ModelShadowGpu(int capacity,Function<String,String> source){this(capacity,ParticleBuffers.CARRIER_CAP,source);}
    public ModelShadowGpu(int capacity,int carrierCapacity,Function<String,String> source){
        if(capacity<1)throw new IllegalArgumentException("Model shadow capacity");this.capacity=capacity;this.carrierCapacity=carrierCapacity;meta=capacity+carrierCapacity;
        try{
            instances=allocate((long)(meta+1)*8);commands=allocate((ParticleBuffers.INDIRECT_UINTS+4L)*4);
            for(int p=0;p<3;p++){
                programs[p]=com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler.compute("ModelShadowGpu", "#version 450 core\n"+ParticlePrograms.commonPrelude()+source.apply("model_shadow_"+new String[]{"prepare","collect","finish"}[p]+".comp"));
                populationLocations[p][0]=GL20.glGetUniformLocation(programs[p],"uPopulationReadEnabled");populationLocations[p][1]=GL20.glGetUniformLocation(programs[p],"uPopulationGeneration");
                for(int i=0;i<NAMES.length;i++)locations[p][i]=GL20.glGetUniformLocation(programs[p],NAMES[i]);
                GL41.glProgramUniform1ui(programs[p],GL20.glGetUniformLocation(programs[p],"uCarrierLimit"),carrierCapacity);
                GL41.glProgramUniform1ui(programs[p],locations[p][0],capacity);GL41.glProgramUniform1ui(programs[p],locations[p][1],capacity);GL41.glProgramUniform1ui(programs[p],locations[p][12],meta);
            }
            int finish=programs[2];GL41.glProgramUniform1ui(finish,GL20.glGetUniformLocation(finish,"uCutout"),AllayModelGeometry.BODY_OPAQUE_INDEX_COUNT);
            GL41.glProgramUniform1ui(finish,GL20.glGetUniformLocation(finish,"uOpaque"),AllayModelGeometry.OPAQUE_INDEX_COUNT);
            GL41.glProgramUniform1ui(finish,GL20.glGetUniformLocation(finish,"uTotal"),AllayModelGeometry.INDICES.length);
        }catch(RuntimeException error){close();throw error;}
    }
    private static int allocate(long bytes){if(bytes>GpuCapabilities.current().storageBlockBytes())throw new IllegalArgumentException("Shadow buffer device limit");
        int id=GL15.glGenBuffers();try{GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,id);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.allocate(GL43.GL_SHADER_STORAGE_BUFFER,bytes,GL15.GL_DYNAMIC_COPY);
            if(GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)!=bytes)throw new IllegalStateException("Shadow storage allocation");return id;
        }catch(RuntimeException error){com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(id);throw error;}}
    public void prepare(int pool,int emitters,int counter,PackageDrawCulling culling,float x,float y,float z,float width,float height,float viewScale,float time,float[] waves,float[] targets,float[] tiers){
        prepare(pool,emitters,counter,culling,x,y,z,width,height,viewScale,time,waves,targets,tiers,0,0);
    }
    public void prepare(int pool,int emitters,int counter,PackageDrawCulling culling,float x,float y,float z,float width,float height,float viewScale,float time,float[] waves,float[] targets,float[] tiers,int population,long generation){
        if(closed)throw new IllegalStateException("Model shadows closed");if(culling.count<0||culling.count>13)throw new IllegalArgumentException("Shadow planes");
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,ParticleBuffers.POPULATION_READ_BB,population);
        for(int i=0;i<programs.length;i++){if(populationLocations[i][0]>=0)GL41.glProgramUniform1i(programs[i],populationLocations[i][0],population>0?1:0);
            if(populationLocations[i][1]>=0)GL41.glProgramUniform2ui(programs[i],populationLocations[i][1],(int)generation,(int)(generation>>>32));}
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT|GL42.GL_COMMAND_BARRIER_BIT);
        try(var stack=MemoryStack.stackPush()){GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER,0,stack.ints(pool,emitters,counter,instances,commands));}
        GL20.glUseProgram(programs[0]);GL43.glDispatchCompute(1,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_COMMAND_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER,commands);GL20.glUseProgram(programs[1]);int[] l=locations[1];
        GL20.glUniform2f(l[2],width,height);GL20.glUniform1f(l[3],viewScale);GL20.glUniform3f(l[4],x,y,z);GL20.glUniform4fv(l[5],culling.planes);GL30.glUniform1ui(l[6],culling.count);
        GL20.glUniform2f(l[7],culling.distance,culling.safe);GL20.glUniform1f(l[8],time);
        if(waves==null&&targets==null&&tiers==null)
            com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.uploadUniforms(programs[1]);
        else {GL20.glUniform4fv(l[9],waves);GL20.glUniform4fv(l[10],targets);GL20.glUniform1fv(l[11],tiers);}
        GL43.glDispatchComputeIndirect(ParticleBuffers.INDIRECT_UINTS*4L);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        GL20.glUseProgram(programs[2]);GL43.glDispatchCompute(1,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT|GL42.GL_COMMAND_BARRIER_BIT);
    }
    public void starfall(int buffer){for(int p:programs)com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.bindPose(p);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,26,buffer);for(int p:programs)GL41.glProgramUniform1i(p,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(p,"uStarfallEnabled"),buffer>0?1:0);}
    public int carrierCapacity(){return carrierCapacity;}
    public int instances(){return instances;}public int commands(){return commands;}public int capacity(){return capacity;}
    public long bytes(){return (long)(meta+1)*8+(ParticleBuffers.INDIRECT_UINTS+4L)*4;}
    @Override public void close(){if(closed)return;closed=true;for(int p:programs)if(p!=0){com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(p);com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms.delete(p);}if(instances!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(instances);if(commands!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(commands);}
}
