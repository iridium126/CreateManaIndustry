package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.gpu.state.GpuBindings;

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
        int pool,counter,commands,first,second,add,opaque,dispatch,feathers,parameters;
        final java.nio.ByteBuffer parameterBytes=org.lwjgl.BufferUtils.createByteBuffer(DisplayParameters.BYTES);
        long frozenGeneration=-1;
        long fence;
        final ModelShadowGroups groups=new ModelShadowGroups();
    }
    private final Slot[] slots={new Slot(),new Slot(),new Slot()};
    private static final int COMMAND_BYTES=ParticleBuffers.INDIRECT_UINTS*4, PROFILE_BYTES=COMMAND_BYTES+44;
    private static final class ProfileSlot {
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReadbackBuffer data;
        long fence,frame,generation;
        boolean lod;
    }
    private final ProfileSlot[] profiles={new ProfileSlot(),new ProfileSlot(),new ProfileSlot()};
    private final java.nio.ByteBuffer profileBytes=java.nio.ByteBuffer.allocate(PROFILE_BYTES).order(java.nio.ByteOrder.nativeOrder());
    private final float[] planes=new float[24];
    private int program,capacity,carrierCapacity,nextSlot;
    private int frozenEmitters;
    private long emitterRevision=-1,emitterEpoch=-1;
    private long identityEpoch=-1;
    private long publicationId;
    private Slot selected;
    private final java.util.function.Function<String,String> sources;
    private final ModelPosePublication poses;
    private final com.iridium126.createmanaindustry.client.particles.engine.spatial.RtRenderVisibility visibility;
    public ModelRenderPublication(){this(ParticlePrograms::loadParticlePlain);}
    public ModelRenderPublication(java.util.function.Function<String,String> sources){this.sources=sources;poses=new ModelPosePublication(sources);visibility=new com.iridium126.createmanaindustry.client.particles.engine.spatial.RtRenderVisibility(sources);}
    public record Frame(long id,int pool,int commands,int counter,int permutation,float alpha,float timeSec) {}

    private static int buffer(long bytes){int b=GL45.glCreateBuffers();GL45.glNamedBufferData(b,Math.max(16,bytes),GL15.GL_DYNAMIC_COPY);if(GL45.glGetNamedBufferParameteri64(b,GL15.GL_BUFFER_SIZE)<bytes){GpuBufferNames.delete(b);throw new IllegalStateException("Display allocation failed");}return b;}
    private static void bind(int at,int buffer){GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,at,buffer);}
    private static void ui(int p,String name,int n){com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(p,GpuUniforms.loc(p,name),n);}
    private static void flag(int p,String name,boolean b){com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(p,GpuUniforms.loc(p,name),b?1:0);}
    private static void barrier(){GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL43.GL_COMMAND_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT);}
    private void phase(int phase,int groups){ui(program,"phase",phase);com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(program);GpuReplayCommands.dispatchCompute(Math.max(1,groups),1,1);barrier();}
    private void allocate(ParticleBuffers gpu){
        if(capacity==gpu.capacity()&&carrierCapacity==gpu.carrierCapacity())return;
        close();capacity=gpu.capacity();carrierCapacity=gpu.carrierCapacity();
        try{
        program=GpuShaderCompiler.compute("display interpolation","#version 450 core\n"+DisplayParameters.prelude()+ParticlePrograms.commonPrelude()+sources.apply("display.comp"));
        DisplayParameters.verify(program);
        GpuProgramContract.installSamplers(program);GpuProgramContract.verify(program,"display.comp");
        frozenEmitters=buffer(GL45.glGetNamedBufferParameteri64(gpu.emitterBufferId(),GL15.GL_BUFFER_SIZE));
        for(Slot s:slots){s.pool=buffer(capacity*64L);s.counter=buffer(16);s.commands=buffer(ParticleBuffers.INDIRECT_UINTS*4L);
            s.first=buffer((gpu.metaSlotIndex()+1L)*8);s.second=buffer((gpu.metaSlotIndex()+1L)*8);
            s.parameters=buffer(DisplayParameters.BYTES);s.add=buffer(capacity*4L);s.opaque=buffer(capacity*4L);s.dispatch=buffer(48);s.feathers=buffer(com.iridium126.createmanaindustry.client.particles.emitter.AllayFeatherLayout.BYTES);}
        }catch(RuntimeException|LinkageError failure){close();throw failure;}
    }
    private Slot freeSlot(){
        // These resources are exclusive to this GL command stream, never CUDA.
        // Order reuse after previous shader/texture reads on the GPU; CPU fence
        // polling must not replace interpolation with a 20 Hz simulation pool.
        Slot s=slots[nextSlot];nextSlot=(nextSlot+1)%slots.length;
        GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT
                |GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL42.GL_UNIFORM_BARRIER_BIT|GL43.GL_COMMAND_BARRIER_BIT);
        if(s.fence!=0){GL32.glDeleteSync(s.fence);s.fence=0;}
        return s;
    }
    public Frame prepare(ParticleBuffers gpu,ParticlePrograms shaders,Publication current,Publication previous,
                         float alpha,float timeSec,Camera camera,Matrix4fc view,Matrix4fc projection){
        endFrame();allocate(gpu);Slot s=freeSlot();
        selected=s;
        if(emitterRevision!=gpu.emitterRevision()||emitterEpoch!=gpu.identityEpoch()){
            long bytes=gpu.uploadedEmitterBytes();
            if(bytes>0)GpuReplayCommands.copyNamedBufferSubData(gpu.emitterBufferId(),frozenEmitters,0,0,bytes);
            emitterRevision=gpu.emitterRevision();emitterEpoch=gpu.identityEpoch();
            barrier();
        }
        s.frozenGeneration=current.generation();
        boolean history=previous!=null&&previous.generation()+1==current.generation()&&identityEpoch==gpu.identityEpoch();
        if(!history){timeSec+=(1-alpha)*.05f;alpha=1;}
        identityEpoch=gpu.identityEpoch();
        bind(0,current.pool());bind(1,gpu.previousParticleBufferId());bind(2,s.pool);bind(3,current.counter());bind(4,history?previous.counter():current.counter());bind(5,gpu.historyBufferId());bind(11,frozenEmitters);
        bind(6,s.counter);bind(7,s.commands);bind(8,s.dispatch);bind(9,s.first);bind(10,current.population());
        gpu.bindOrdinaryRead(program,false,current.generation());
        var cameraNow=camera.getPosition();var currentDelta=gpu.currentCameraOrigin().subtract(cameraNow);var previousDelta=gpu.previousCameraOrigin().subtract(cameraNow);
        DisplayParameters.write(s.parameterBytes,capacity,gpu.historyWordBase(),gpu.metaSlotIndex(),carrierCapacity,history?alpha:1,currentDelta,previousDelta);
        GpuReplayCommands.namedBufferSubData(s.parameters,0,s.parameterBytes);
        GpuBindings.bindBase(GL31.GL_UNIFORM_BUFFER,DisplayParameters.BINDING,s.parameters);
        GpuReplayCommands.copyNamedBufferSubData(current.commands(),s.commands,0,0,ParticleBuffers.INDIRECT_UINTS*4L);
        StarfallGpuBuffers.INSTANCE.bind(program,false);StarfallGpuBuffers.INSTANCE.bindDisplayHistory(program);
        StarfallGpuBuffers.INSTANCE.bindTexture(com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.SAMPLER_MERGED_STARFALL);
        long nativeGameTime=StarfallGpuBuffers.INSTANCE.nativeDisplayGameTime();
        float trajectoryTime=nativeGameTime<0?timeSec:(float)(((nativeGameTime&((1L<<21)-1))-(1.-alpha))*.05);
        float trajectoryOffset=trajectoryTime-AllayStormRuntime.INSTANCE.timeSec();
        if(trajectoryOffset>52428.8f)trajectoryOffset-=104857.6f;
        if(trajectoryOffset< -52428.8f)trajectoryOffset+=104857.6f;
        StarfallGpuBuffers.INSTANCE.bindRenderClock(program,trajectoryOffset);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1f(program,GpuUniforms.loc(program,"uTimeSec"),trajectoryTime<0?trajectoryTime+104857.6f:trajectoryTime);
        bind(20,s.feathers);phase(3,1);
        GpuBindings.bind(GL43.GL_DISPATCH_INDIRECT_BUFFER,s.dispatch);ui(program,"phase",2);GpuReplayCommands.dispatchComputeIndirect(36);barrier();
        gpu.displayVisibility(visibility.prepare(gpu,s.pool,s.counter,frozenEmitters,s.dispatch,current.generation(),timeSec,cameraNow));
        float far=1;while(far<Math.max(16,ClientConfig.particleFadeDistance*2))far*=2;
        var matrix=new Matrix4f(projection).mul(view);var plane=new Vector4f();for(int k=0;k<6;k++){matrix.frustumPlane(k,plane);plane.normalize3();planes[k*4]=plane.x;planes[k*4+1]=plane.y;planes[k*4+2]=plane.z;planes[k*4+3]=plane.w;}
        var pos=camera.getPosition();float[] viewValues=view.get(new float[16]);
        for(int role=1;role<=2;role++){
            gpu.beginBindings();int keygen=role==1?shaders.ordinaryKeygen():shaders.keygen();com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(keygen);
            bind(0,s.pool);bind(2,s.commands);bind(3,s.counter);bind(5,frozenEmitters);
            bind(ParticleBuffers.SORTWRITE_BINDING,s.first);bind(ParticleBuffers.CARRIERSINK_BB,s.second);bind(ParticleBuffers.ORDERADD_BINDING,s.add);bind(ParticleBuffers.ORDEROPAQUE_BINDING,s.opaque);
            if(role==1){gpu.bindOrdinaryRead(keygen,false,current.generation());AllayFeatherGpu.INSTANCE.publishDisplay(keygen,s.feathers);}
            else {
                gpu.bindVisibility(keygen);gpu.bindPopulationRead(keygen,false,current.generation());
                StarfallGpuBuffers.INSTANCE.bind(keygen,false);StarfallGpuBuffers.INSTANCE.uploadUniforms(keygen);StarfallGpuBuffers.INSTANCE.bindRenderClock(keygen,timeSec-AllayStormRuntime.INSTANCE.timeSec());
                var storm=AllayStormRuntime.INSTANCE;var center=storm.center();s.groups.begin(keygen,0,gpu.modelIndexCapacity(),current.generation(),StormShadowSnapshot.of(current.generation(),storm.active(),center.x,center.y,center.z,storm.radius()),planes,pos);
            }
            flag(keygen,"uPopulationWriteEnabled",false);
            ui(keygen,"uUpper",capacity);ui(keygen,"uPackageStaged",0);ui(keygen,"uCarrierBase",gpu.carrierBase());ui(keygen,"uCarrierLimit",carrierCapacity);
            com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1f(keygen,GpuUniforms.loc(keygen,"uTimeSec"),timeSec);
            com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform3f(keygen,GpuUniforms.loc(keygen,"uCamPos"),(float)pos.x,(float)pos.y,(float)pos.z);
            GL41.glProgramUniformMatrix4fv(keygen,GpuUniforms.loc(keygen,"uView"),false,viewValues);
            com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1f(keygen,GpuUniforms.loc(keygen,"uMaxDepth"),far);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1f(keygen,GpuUniforms.loc(keygen,"uDepthLogRange"),Math.getExponent(far));
            GL41.glProgramUniform4fv(keygen,GpuUniforms.loc(keygen,"uFrustum"),planes);
            if(role==2)StormLodGpu.INSTANCE.keygen(keygen);
            GpuBindings.bind(GL43.GL_DISPATCH_INDIRECT_BUFFER,s.dispatch);
            GpuReplayCommands.dispatchComputeIndirect(role==1?0:12);barrier();
        }
        s.groups.commit(0,current.generation());
        bind(7,s.commands);bind(8,s.dispatch);bind(9,s.first);phase(4,1);
        int read=s.first,write=s.second;
        for(int pass=0;pass<ParticleBuffers.RADIX_PASSES;pass++){
            gpu.beginBindings();gpu.clearHist();bind(2,s.commands);gpu.bindHist();gpu.bindOffsets();
            int p=shaders.radixHist();com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(p);bind(ParticleBuffers.SORTREAD_BINDING,read);ui(p,"uShift",pass*Integer.numberOfTrailingZeros(ParticleBuffers.RADIX_BINS));GpuReplayCommands.dispatchComputeIndirect(24);barrier();
            p=shaders.radixScan();com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(p);GpuReplayCommands.dispatchCompute(1,1,1);barrier();
            p=shaders.radixScatter();com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(p);bind(ParticleBuffers.SORTREAD_BINDING,read);bind(ParticleBuffers.SORTWRITE_BINDING,write);ui(p,"uShift",pass*Integer.numberOfTrailingZeros(ParticleBuffers.RADIX_BINS));GpuReplayCommands.dispatchComputeIndirect(24);barrier();int swap=read;read=write;write=swap;
        }
        if(read!=s.first)GpuReplayCommands.copyNamedBufferSubData(s.first,read,gpu.metaSlotIndex()*8L,gpu.metaSlotIndex()*8L,8);
        gpu.setDisplayResources(s.pool,s.commands,s.add,s.opaque,read,s.groups.buffer(0,current.generation()),history?alpha:1);
        com.iridium126.createmanaindustry.client.greattrick.sharedfate.SharedFateGpu.INSTANCE.publishDisplay(
                current.generation(),history?alpha:1,cameraNow);
        int animation=poses.publish(gpu,current,s.pool,frozenEmitters,s.dispatch,timeSec,history?alpha:1);
        gpu.displayFrozenInputs(frozenEmitters,animation,poses.stamp());gpu.beginBindings();return new Frame(++publicationId,s.pool,s.commands,s.counter,read,history?alpha:1,timeSec);
    }
    public void endFrame(){if(selected!=null){selected.fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);selected=null;}}
    /** Draw counts come from the frozen publication, independently of simulation cadence. */
    public void captureCounts(){
        for(var slot:profiles)if(slot.fence!=0){
            int status=GL32.glClientWaitSync(slot.fence,0,0);
            if(status==GL32.GL_TIMEOUT_EXPIRED)continue;
            if(status!=GL32.GL_WAIT_FAILED){
                profileBytes.clear();slot.data.readCompleted(0,PROFILE_BYTES,profileBytes);
                ParticleDiagnostics.INSTANCE.displayCounts(slot.frame,slot.generation,
                    profileBytes.getInt(ParticleBuffers.IDX_CNT_MODELOP*4),profileBytes.getInt(ParticleBuffers.IDX_CNT_CARRIER*4),
                    profileBytes.getInt(ParticleBuffers.IDX_CNT_GHOST*4),profileBytes.getInt(COMMAND_BYTES),
                    slot.lod?profileBytes.getInt(COMMAND_BYTES+8):0,slot.lod?profileBytes.getInt(COMMAND_BYTES+28):0,
                    profileBytes.getInt(ParticleBuffers.IDX_CNT_ADD*4),profileBytes.getInt(ParticleBuffers.IDX_CNT_SPRITE*4),profileBytes.getInt(ParticleBuffers.IDX_CNT_ALPHA*4));
            }
            GL32.glDeleteSync(slot.fence);slot.fence=0;
        }
        if(selected==null||(!ClientFrameCapture.INSTANCE.collecting()&&!ParticleDiagnostics.INSTANCE.enabled()))return;
        for(var slot:profiles)if(slot.fence==0){
            if(slot.data==null)slot.data=new com.iridium126.createmanaindustry.client.gpu.buffer.GpuReadbackBuffer(PROFILE_BYTES);
            int target=slot.data.id(),lod=StormLodGpu.INSTANCE.displayCommands(selected.frozenGeneration);
            GpuReplayCommands.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
            GpuReplayCommands.copyNamedBufferSubData(selected.commands,target,0,0,COMMAND_BYTES);
            GpuReplayCommands.copyNamedBufferSubData(selected.feathers,target,3*4,COMMAND_BYTES,4);
            if(lod!=0)GpuReplayCommands.copyNamedBufferSubData(lod,target,0,COMMAND_BYTES+4,40);
            slot.frame=com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.renderFrameToken();
            slot.generation=selected.frozenGeneration;slot.lod=lod!=0;slot.fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
            return;
        }
    }
    @Override public void close(){
        endFrame();poses.close();visibility.close();
        for(var slot:profiles){if(slot.fence!=0)GL32.glDeleteSync(slot.fence);slot.fence=0;if(slot.data!=null)slot.data.close();slot.data=null;}
        for(Slot s:slots){
            s.groups.close();if(s.fence!=0)GL32.glDeleteSync(s.fence);s.fence=0;
            for(int id:new int[]{s.pool,s.counter,s.commands,s.first,s.second,s.add,s.opaque,s.dispatch,s.feathers,s.parameters})GpuBufferNames.delete(id);
            s.pool=s.counter=s.commands=s.first=s.second=s.add=s.opaque=s.dispatch=s.feathers=s.parameters=0;s.frozenGeneration=-1;
        }
        GpuBufferNames.delete(frozenEmitters);frozenEmitters=0;emitterRevision=emitterEpoch=-1;
        GpuPrograms.delete(program);program=capacity=carrierCapacity=nextSlot=0;identityEpoch=-1;
    }
}
