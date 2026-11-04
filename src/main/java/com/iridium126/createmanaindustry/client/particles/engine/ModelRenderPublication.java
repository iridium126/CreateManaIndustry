package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.allaystorm.*;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames;
import com.iridium126.createmanaindustry.client.gpu.frame.GpuFrameCoordinator.Publication;
import com.iridium126.createmanaindustry.client.gpu.shader.*;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import net.minecraft.client.Camera;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands;

/** Display buffers have their own ownership. Simulation buffers are never interpolation targets. */
public final class ModelRenderPublication implements AutoCloseable {
    private static final class Slot {
        int pool,counter,commands,first,second,add,opaque,dispatch,animation,animationTexture,emitters,feathers;
        long frozenGeneration=-1;
        long fence;
        final ModelShadowGroups groups=new ModelShadowGroups();
    }
    private final Slot[] slots={new Slot(),new Slot(),new Slot()};
    private final float[] planes=new float[24];
    private int program,capacity,carrierCapacity;
    private long emitterBytes;
    private long identityEpoch=-1;
    private Slot selected;
    private final java.util.function.Function<String,String> sources;
    public ModelRenderPublication(){this(ParticlePrograms::loadParticlePlain);}
    public ModelRenderPublication(java.util.function.Function<String,String> sources){this.sources=sources;}
    public record Frame(int pool,int commands,int counter,int permutation,float alpha,float timeSec) {}

    private static int buffer(long bytes){int b=GL45.glCreateBuffers();GL45.glNamedBufferData(b,Math.max(16,bytes),GL15.GL_DYNAMIC_COPY);if(GL45.glGetNamedBufferParameteri64(b,GL15.GL_BUFFER_SIZE)<bytes){GpuBufferNames.delete(b);throw new IllegalStateException("Display allocation failed");}return b;}
    private static void bind(int at,int buffer){GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,at,buffer);}
    private static void ui(int p,String name,int n){GL41.glProgramUniform1ui(p,GpuUniforms.loc(p,name),n);}
    private static void flag(int p,String name,boolean b){GL41.glProgramUniform1i(p,GpuUniforms.loc(p,name),b?1:0);}
    private static void barrier(){GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL43.GL_COMMAND_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT);}
    private void phase(int phase,int groups){ui(program,"phase",phase);GL20.glUseProgram(program);GpuReplayCommands.dispatchCompute(Math.max(1,groups),1,1);barrier();}
    private void allocate(ParticleBuffers gpu){
        if(capacity==gpu.capacity()&&carrierCapacity==gpu.carrierCapacity())return;
        close();capacity=gpu.capacity();carrierCapacity=gpu.carrierCapacity();
        try{
        program=GpuShaderCompiler.compute("display interpolation","#version 450 core\n"+ParticlePrograms.commonPrelude()+sources.apply("display.comp"));
        emitterBytes=GL45.glGetNamedBufferParameteri64(gpu.emitterBufferId(),GL15.GL_BUFFER_SIZE);
        for(Slot s:slots){s.pool=buffer(capacity*64L);s.counter=buffer(16);s.commands=buffer(ParticleBuffers.INDIRECT_UINTS*4L);
            s.first=buffer((gpu.metaSlotIndex()+1L)*8);s.second=buffer((gpu.metaSlotIndex()+1L)*8);
            s.emitters=buffer(emitterBytes);s.add=buffer(capacity*4L);s.opaque=buffer(capacity*4L);s.dispatch=buffer(36);s.feathers=buffer(com.iridium126.createmanaindustry.client.particles.emitter.AllayFeatherLayout.BYTES);}
        }catch(RuntimeException|LinkageError failure){close();throw failure;}
    }
    private Slot freeSlot(){
        for(Slot s:slots){if(s.fence!=0){int state=GL32.glClientWaitSync(s.fence,GL32.GL_SYNC_FLUSH_COMMANDS_BIT,0);
            if(state==GL32.GL_TIMEOUT_EXPIRED)continue;if(state==GL32.GL_WAIT_FAILED)throw new IllegalStateException("Display retirement failed");GL32.glDeleteSync(s.fence);s.fence=0;}return s;}return null;
    }
    public Frame prepare(ParticleBuffers gpu,ParticlePrograms shaders,Publication current,Publication previous,
                         float alpha,float timeSec,Camera camera,Matrix4fc view,Matrix4fc projection){
        endFrame();allocate(gpu);Slot s=freeSlot();
        if(s==null)return new Frame(current.pool(),current.commands(),current.counter(),current.permutation(),1,timeSec+(1-alpha)*.05f);
        selected=s;
        if(s.frozenGeneration!=current.generation()){
            GpuReplayCommands.copyNamedBufferSubData(gpu.emitterBufferId(),s.emitters,0,0,emitterBytes);
            var animation=gpu.allayAnimation();
            if(animation.allocated()){
                if(s.animation==0){s.animation=buffer(AllayAnimationGpu.CAPACITY*(long)AllayAnimationGpu.BYTES);s.animationTexture=GL45.glCreateTextures(GL31.GL_TEXTURE_BUFFER);GL45.glTextureBuffer(s.animationTexture,GL30.GL_RGBA32UI,s.animation);}
                GpuReplayCommands.copyNamedBufferSubData(animation.buffer(animation.readBank()),s.animation,0,0,AllayAnimationGpu.CAPACITY*(long)AllayAnimationGpu.BYTES);
            }
            s.frozenGeneration=current.generation();barrier();
        }
        boolean history=previous!=null&&previous.generation()+1==current.generation()&&identityEpoch==gpu.identityEpoch();
        if(!history){timeSec+=(1-alpha)*.05f;alpha=1;}
        identityEpoch=gpu.identityEpoch();
        bind(0,current.pool());bind(1,gpu.previousParticleBufferId());bind(2,s.pool);bind(3,current.counter());bind(4,history?previous.counter():current.counter());bind(5,gpu.historyBufferId());bind(11,s.emitters);
        bind(6,s.counter);bind(7,s.commands);bind(8,s.dispatch);bind(9,s.first);bind(10,current.population());
        ui(program,"capacity",capacity);ui(program,"historyBase",gpu.historyWordBase());ui(program,"metaSlot",gpu.metaSlotIndex());ui(program,"carrierLimit",carrierCapacity);
        GL41.glProgramUniform1f(program,GpuUniforms.loc(program,"alpha"),history?alpha:1);
        var cameraNow=camera.getPosition();var currentDelta=gpu.currentCameraOrigin().subtract(cameraNow);var previousDelta=gpu.previousCameraOrigin().subtract(cameraNow);
        GL41.glProgramUniform3f(program,GpuUniforms.loc(program,"currentCameraDelta"),(float)currentDelta.x,(float)currentDelta.y,(float)currentDelta.z);
        GL41.glProgramUniform3f(program,GpuUniforms.loc(program,"previousCameraDelta"),(float)previousDelta.x,(float)previousDelta.y,(float)previousDelta.z);
        GpuReplayCommands.copyNamedBufferSubData(current.commands(),s.commands,0,0,ParticleBuffers.INDIRECT_UINTS*4L);
        StarfallGpuBuffers.INSTANCE.bind(program,false);StarfallGpuBuffers.INSTANCE.bindRenderClock(program,timeSec-AllayStormRuntime.INSTANCE.timeSec());
        GL41.glProgramUniform1f(program,GpuUniforms.loc(program,"uTimeSec"),timeSec);
        bind(20,s.feathers);phase(2,(capacity+255)/256);phase(3,1);
        gpu.beginBindings();int keygen=shaders.keygen();GL20.glUseProgram(keygen);bind(0,s.pool);bind(2,s.commands);bind(3,s.counter);bind(5,s.emitters);
        bind(ParticleBuffers.SORTWRITE_BINDING,s.first);bind(ParticleBuffers.CARRIERSINK_BB,s.second);bind(ParticleBuffers.ORDERADD_BINDING,s.add);bind(ParticleBuffers.ORDEROPAQUE_BINDING,s.opaque);
        gpu.bindPopulationRead(keygen,false,current.generation());flag(keygen,"uPopulationWriteEnabled",false);AllayFeatherGpu.INSTANCE.publishDisplay(keygen,s.feathers);
        ui(keygen,"uUpper",capacity);ui(keygen,"uPackageStaged",0);ui(keygen,"uCarrierBase",gpu.carrierBase());ui(keygen,"uCarrierLimit",carrierCapacity);
        StarfallGpuBuffers.INSTANCE.bind(keygen,false);StarfallGpuBuffers.INSTANCE.uploadUniforms(keygen);StarfallGpuBuffers.INSTANCE.bindRenderClock(keygen,timeSec-AllayStormRuntime.INSTANCE.timeSec());
        GL41.glProgramUniform1f(keygen,GpuUniforms.loc(keygen,"uTimeSec"),timeSec);
        var pos=camera.getPosition();GL41.glProgramUniform3f(keygen,GpuUniforms.loc(keygen,"uCamPos"),(float)pos.x,(float)pos.y,(float)pos.z);
        GL41.glProgramUniformMatrix4fv(keygen,GpuUniforms.loc(keygen,"uView"),false,view.get(new float[16]));
        float far=1;while(far<Math.max(16,ClientConfig.particleFadeDistance*2))far*=2;
        GL41.glProgramUniform1f(keygen,GpuUniforms.loc(keygen,"uMaxDepth"),far);GL41.glProgramUniform1f(keygen,GpuUniforms.loc(keygen,"uDepthLogRange"),Math.getExponent(far));
        var matrix=new Matrix4f(projection).mul(view);var plane=new Vector4f();for(int k=0;k<6;k++){matrix.frustumPlane(k,plane);plane.normalize3();planes[k*4]=plane.x;planes[k*4+1]=plane.y;planes[k*4+2]=plane.z;planes[k*4+3]=plane.w;}
        GL41.glProgramUniform4fv(keygen,GpuUniforms.loc(keygen,"uFrustum"),planes);
        var storm=AllayStormRuntime.INSTANCE;var center=storm.center();s.groups.begin(keygen,0,gpu.modelIndexCapacity(),current.generation(),StormShadowSnapshot.of(current.generation(),storm.active(),center.x,center.y,center.z,storm.radius()),planes,pos);
        StormLodGpu.INSTANCE.keygen(keygen);GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER,s.dispatch);
        ui(keygen,"uModelPass",1);GpuReplayCommands.dispatchComputeIndirect(0);barrier();ui(keygen,"uModelPass",2);GpuReplayCommands.dispatchComputeIndirect(12);barrier();s.groups.commit(0,current.generation());
        bind(7,s.commands);bind(8,s.dispatch);bind(9,s.first);phase(4,1);
        int read=s.first,write=s.second;
        for(int pass=0;pass<ParticleBuffers.RADIX_PASSES;pass++){
            gpu.beginBindings();gpu.clearHist();bind(2,s.commands);gpu.bindHist();gpu.bindOffsets();
            int p=shaders.radixHist();GL20.glUseProgram(p);bind(ParticleBuffers.SORTREAD_BINDING,read);ui(p,"uShift",pass*Integer.numberOfTrailingZeros(ParticleBuffers.RADIX_BINS));GpuReplayCommands.dispatchComputeIndirect(24);barrier();
            p=shaders.radixScan();GL20.glUseProgram(p);GpuReplayCommands.dispatchCompute(1,1,1);barrier();
            p=shaders.radixScatter();GL20.glUseProgram(p);bind(ParticleBuffers.SORTREAD_BINDING,read);bind(ParticleBuffers.SORTWRITE_BINDING,write);ui(p,"uShift",pass*Integer.numberOfTrailingZeros(ParticleBuffers.RADIX_BINS));GpuReplayCommands.dispatchComputeIndirect(24);barrier();int swap=read;read=write;write=swap;
        }
        if(read!=s.first)GpuReplayCommands.copyNamedBufferSubData(s.first,read,gpu.metaSlotIndex()*8L,gpu.metaSlotIndex()*8L,8);
        gpu.setDisplayResources(s.pool,s.commands,s.add,s.opaque,read,s.groups.buffer(0,current.generation()),history?alpha:1);
        com.iridium126.createmanaindustry.client.greattrick.sharedfate.SharedFateGpu.INSTANCE.publishDisplay(history?alpha:1,cameraNow);
        gpu.displayFrozenInputs(s.emitters,s.animationTexture);gpu.beginBindings();return new Frame(s.pool,s.commands,s.counter,read,history?alpha:1,timeSec);
    }
    public void endFrame(){if(selected!=null){selected.fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);selected=null;}}
    @Override public void close(){endFrame();for(Slot s:slots){s.groups.close();if(s.fence!=0)GL32.glDeleteSync(s.fence);s.fence=0;if(s.animationTexture!=0)com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(s.animationTexture);s.animationTexture=0;for(int id:new int[]{s.pool,s.counter,s.commands,s.first,s.second,s.add,s.opaque,s.dispatch,s.animation,s.emitters,s.feathers})GpuBufferNames.delete(id);s.pool=s.counter=s.commands=s.first=s.second=s.add=s.opaque=s.dispatch=s.animation=s.emitters=s.feathers=0;s.frozenGeneration=-1;}GpuPrograms.delete(program);program=capacity=carrierCapacity=0;identityEpoch=-1;}
}
