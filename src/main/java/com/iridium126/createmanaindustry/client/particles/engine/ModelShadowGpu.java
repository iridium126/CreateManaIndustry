package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.gpu.state.GpuBindings;

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
    private final int[][] viewLocations=new int[2][5];
    private final float[] viewPlanes=new float[24];
    private float rayX,rayY,rayZ;
    private boolean viewEnabled,memberView;
    private int groupBuffer;
    private int visibilityBuffer;
    public void visibility(int buffer){visibilityBuffer=buffer;}
    private boolean viewUploaded;
    private int lastGroup;
    private ShadowDepthContext shadowDepth,screenDepth;
    private boolean depthUploaded;
    private final int[] depthLocations=new int[13];
    private final boolean depthPrograms;
    private static final String[] NAMES={"uCapacity","uCarrierBase","uNativeSize","uEntityViewScale","uCamera","uFrustum","uFrustumCount","uCullBounds","uTimeSec","uWave","uWaveTarget","uWaveTier","uMetaSlot","uCutout"};
    public ModelShadowGpu(int capacity,Function<String,String> source){this(capacity,ParticleBuffers.CARRIER_CAP,source);}
    public ModelShadowGpu(int capacity,int carrierCapacity,Function<String,String> source){
        this(capacity,carrierCapacity,source,false);
    }
    /** Optional native-depth candidate is isolated from the qualified FULL kernel. */
    public ModelShadowGpu(int capacity,int carrierCapacity,Function<String,String> source,boolean depthPrograms){
        this.depthPrograms=depthPrograms;
        if(capacity<1)throw new IllegalArgumentException("Model shadow capacity");this.capacity=capacity;this.carrierCapacity=carrierCapacity;meta=capacity+carrierCapacity;
        try{
            instances=allocate((long)(meta+1)*8);commands=allocate((ParticleBuffers.INDIRECT_UINTS+4L)*4);
            for(int p=0;p<3;p++){
                programs[p]=com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler.compute("ModelShadowGpu", "#version 450 core\n#define CMI_SHADOW_DEPTH "+(depthPrograms?1:0)+"\n"+ParticlePrograms.commonPrelude()+source.apply("model_shadow_"+new String[]{"prepare","collect","finish"}[p]+".comp"));
                populationLocations[p][0]=GL20.glGetUniformLocation(programs[p],"uPopulationReadEnabled");populationLocations[p][1]=GL20.glGetUniformLocation(programs[p],"uPopulationGeneration");
                for(int i=0;i<NAMES.length;i++)locations[p][i]=GL20.glGetUniformLocation(programs[p],NAMES[i]);
                if(p<2){String[] names={"uShadowViewEnabled","uShadowMemberView","uShadowViewPlanes","uShadowRay","uShadowGroupsRead"};
                    for(int j=0;j<names.length;j++)viewLocations[p][j]=GL20.glGetUniformLocation(programs[p],names[j]);}
                com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(programs[p],GL20.glGetUniformLocation(programs[p],"uCarrierLimit"),carrierCapacity);
                com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(programs[p],locations[p][0],capacity);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(programs[p],locations[p][1],capacity);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(programs[p],locations[p][12],meta);
            }
            int finish=programs[2];com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(finish,GL20.glGetUniformLocation(finish,"uCutout"),AllayModelGeometry.BODY_OPAQUE_INDEX_COUNT);
            String[] depthNames={"uShadowDepthEnabled","uScreenDepthEnabled","uShadowDepthReverse","uShadowDepthMatrix","uScreenDepthMatrix","uShadowDepthParams","uShadowDepthFilter","uScreenOriginDelta","uScreenDepthMargin","uShadowDepthSourceSize","uScreenDepthSourceSize","uShadowDepthHierarchy","uScreenDepthHierarchy"};
            for(int i=0;i<depthNames.length;i++)depthLocations[i]=GL20.glGetUniformLocation(programs[1],depthNames[i]);
            com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(finish,GL20.glGetUniformLocation(finish,"uOpaque"),AllayModelGeometry.OPAQUE_INDEX_COUNT);
            com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(finish,GL20.glGetUniformLocation(finish,"uTotal"),AllayModelGeometry.INDICES.length);
        }catch(RuntimeException error){close();throw error;}
    }
    private static int allocate(long bytes){if(bytes>GpuCapabilities.current().storageBlockBytes())throw new IllegalArgumentException("Shadow buffer device limit");
        int id=GL15.glGenBuffers();try{GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,id);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.allocate(GL43.GL_SHADER_STORAGE_BUFFER,bytes,GL15.GL_DYNAMIC_COPY);
            if(GL32.glGetBufferParameteri64(GL43.GL_SHADER_STORAGE_BUFFER,GL15.GL_BUFFER_SIZE)!=bytes)throw new IllegalStateException("Shadow storage allocation");return id;
        }catch(RuntimeException error){com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(id);throw error;}}
    /** Null planes preserve the original FULL semantics. No GL queries or allocations per submission. */
    public void culling(float[] planes,float dx,float dy,float dz,boolean perMember,int groups){
        viewEnabled=planes!=null;memberView=perMember;groupBuffer=groups;
        if(planes!=null){if(planes.length!=24)throw new IllegalArgumentException("Shadow receiver planes");
            for(float value:planes)if(!Float.isFinite(value))throw new IllegalArgumentException("Nonfinite shadow plane");
            if(!Float.isFinite(dx)||!Float.isFinite(dy)||!Float.isFinite(dz)||dx*dx+dy*dy+dz*dz<.5f)throw new IllegalArgumentException("Shadow ray");
            System.arraycopy(planes,0,viewPlanes,0,24);rayX=dx;rayY=dy;rayZ=dz;}
    }
    public void depth(ShadowDepthContext shadow,ShadowDepthContext screen){if(!depthPrograms&&(shadow!=null||screen!=null))throw new IllegalStateException("Native depth program is not qualified/selected");shadowDepth=shadow;screenDepth=screen;}
    private void uploadDepth(){
        if(shadowDepth==null&&screenDepth==null&&!depthUploaded)return;
        int p=programs[1];com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(p,depthLocations[0],shadowDepth!=null?1:0);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(p,depthLocations[1],screenDepth!=null&&viewEnabled?1:0);
        if(shadowDepth!=null){var d=shadowDepth;GL45.glBindTextureUnit(4,d.texture());GL33.glBindSampler(4,d.sampler());
            com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(p,depthLocations[2],d.reversed()?1:0);GL41.glProgramUniformMatrix4fv(p,depthLocations[3],false,d.matrix());
            com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform4f(p,depthLocations[5],d.distortion(),d.amount(),d.depthScale(),d.margin());com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1f(p,depthLocations[6],d.filter());
            com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform2i(p,depthLocations[9],d.width(),d.height());}
        if(screenDepth!=null&&viewEnabled){var d=screenDepth;GL45.glBindTextureUnit(5,d.texture());GL33.glBindSampler(5,d.sampler());
            GL41.glProgramUniformMatrix4fv(p,depthLocations[4],false,d.matrix());com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform3f(p,depthLocations[7],d.originX(),d.originY(),d.originZ());
            com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1f(p,depthLocations[8],d.margin());com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform2i(p,depthLocations[10],d.width(),d.height());}
        depthUploaded=shadowDepth!=null||screenDepth!=null;
    }
    public void prepare(int pool,int emitters,int counter,PackageDrawCulling culling,float x,float y,float z,float width,float height,float viewScale,float time,float[] waves,float[] targets,float[] tiers){
        prepare(pool,emitters,counter,culling,x,y,z,width,height,viewScale,time,waves,targets,tiers,0,0);
    }
    public void prepare(int pool,int emitters,int counter,PackageDrawCulling culling,float x,float y,float z,float width,float height,float viewScale,float time,float[] waves,float[] targets,float[] tiers,int population,long generation){
        if(closed)throw new IllegalStateException("Model shadows closed");if(culling.count<0||culling.count>13)throw new IllegalArgumentException("Shadow planes");
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(programs[1],com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(programs[1],"uRtVisibleGroups"),visibilityBuffer!=0?1:0);
        if(visibilityBuffer!=0)GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,29,visibilityBuffer);
        if(groupBuffer>0||lastGroup>0)GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,ModelShadowGroups.BINDING,groupBuffer);
        lastGroup=groupBuffer;
        if(viewEnabled||viewUploaded)for(int p=0;p<2;p++){int id=programs[p];int[] v=viewLocations[p];
            com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(id,v[0],viewEnabled?1:0);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(id,v[1],memberView?1:0);
            com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(id,v[4],groupBuffer>0&&viewEnabled?1:0);
            if(p==0)com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform3f(id,locations[p][4],x,y,z);
            if(viewEnabled){GL41.glProgramUniform4fv(id,v[2],viewPlanes);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform3f(id,v[3],rayX,rayY,rayZ);}}
        viewUploaded=viewEnabled;
        uploadDepth();
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,ParticleBuffers.POPULATION_READ_BB,population);
        for(int i=0;i<programs.length;i++){if(populationLocations[i][0]>=0)com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(programs[i],populationLocations[i][0],population>0?1:0);
            if(populationLocations[i][1]>=0)com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform2ui(programs[i],populationLocations[i][1],(int)generation,(int)(generation>>>32));}
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT|GL42.GL_COMMAND_BARRIER_BIT);
        try(var stack=MemoryStack.stackPush()){GpuBindings.bindBases(GL43.GL_SHADER_STORAGE_BUFFER,0,stack.ints(pool,emitters,counter,instances,commands));}
        com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(programs[0]);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute(1,1,1);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_COMMAND_BARRIER_BIT);
        GpuBindings.bind(GL43.GL_DISPATCH_INDIRECT_BUFFER,commands);com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(programs[1]);int[] l=locations[1];
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current2f(l[2],width,height);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current1f(l[3],viewScale);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current3f(l[4],x,y,z);GL20.glUniform4fv(l[5],culling.planes);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current1ui(l[6],culling.count);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current2f(l[7],culling.distance,culling.safe);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current1f(l[8],time);
        if(waves==null&&targets==null&&tiers==null)
            com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.uploadUniforms(programs[1]);
        else {GL20.glUniform4fv(l[9],waves);GL20.glUniform4fv(l[10],targets);GL20.glUniform1fv(l[11],tiers);}
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchComputeIndirect(ParticleBuffers.INDIRECT_UINTS*4L);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(programs[2]);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute(1,1,1);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT|GL42.GL_COMMAND_BARRIER_BIT);
    }
    public void starfall(int buffer){starfall(buffer,0);}
    public void starfall(int buffer,float clockOffset){for(int p:programs){com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.bindPose(p);com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.bindRenderClock(p,clockOffset);}GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,26,buffer);for(int p:programs)com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(p,com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.loc(p,"uStarfallEnabled"),buffer>0?1:0);}
    public int carrierCapacity(){return carrierCapacity;}
    public int instances(){return instances;}public int commands(){return commands;}public int capacity(){return capacity;}
    public long bytes(){return (long)(meta+1)*8+(ParticleBuffers.INDIRECT_UINTS+4L)*4;}
    @Override public void close(){if(closed)return;closed=true;for(int p:programs)if(p!=0){com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.clearUniformProgram(p);com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms.delete(p);}if(instances!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(instances);if(commands!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(commands);}
}
