package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuProgramContract;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler;
import com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms;
import com.iridium126.createmanaindustry.client.gpu.state.GpuBindings;
import com.iridium126.createmanaindustry.client.particles.engine.spatial.NativeStormSimulation;
import org.lwjgl.opengl.*;
import static com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.*;

/** GL rendering projection of the native Storm, using the existing model allocator and history. */
final class StormProjectionGpu implements AutoCloseable {
    private static final int MEMBERS=131072;
    private int program, map, args, stamp;
    private long sequence, identity, resourceEpoch;
    private long shownSequence, shownIdentity;
    private long candidateSequence, candidateIdentity, candidateEpoch;
    private final int[] playback=new int[2];
    private final int[] playbackViews=new int[2];
    private final NativeStormDisplayClock clock=new NativeStormDisplayClock();
    private int playbackBank,playbackStamp;
    private long candidateWorldTick;
    private boolean writePlayback,samePlaybackIdentity;
    private NativeStormDisplayClock.Transition transition;

    NativeStormDisplayClock.View playback(double worldTick,boolean paused){
        if(identity==0||playbackStamp==0)return null;
        return new NativeStormDisplayClock.View(playbackViews[playbackBank],playbackStamp,clock.sample(worldTick,System.nanoTime(),paused));
    }

    private void ensure(){
        if(program!=0)return;
        try{
            program=GpuShaderCompiler.compute("native storm projection","#version 450 core\n"+
                    ParticlePrograms.commonPrelude()+ParticlePrograms.loadParticlePlain("storm_projection.comp"));
            GpuProgramContract.installSamplers(program);
            GpuProgramContract.verify(program,"storm_projection.comp");
            map=GL45.glCreateBuffers();
            GL45.glNamedBufferData(map,MEMBERS*16L,GL15.GL_DYNAMIC_COPY);
            if(GL45.glGetNamedBufferParameteri64(map,GL15.GL_BUFFER_SIZE)!=MEMBERS*16L)
                throw new IllegalStateException("Native Storm projection map allocation failed");
            clearMap();
            args=GL45.glCreateBuffers();
            GL45.glNamedBufferData(args,12L,GL15.GL_DYNAMIC_COPY);
            for(int i=0;i<playback.length;i++){
                playback[i]=GL45.glCreateBuffers();
                GL45.glNamedBufferData(playback[i],MEMBERS*64L,GL15.GL_DYNAMIC_COPY);
                playbackViews[i]=GL45.glCreateTextures(GL31.GL_TEXTURE_BUFFER);
                GL45.glTextureBuffer(playbackViews[i],GL30.GL_RGBA32UI,playback[i]);
            }
        }catch(RuntimeException|LinkageError failure){close();throw failure;}
    }
    private void clearMap(){
        GpuReplayCommands.clearNamedBufferData(map,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(java.nio.IntBuffer)null);
    }
    void stage(ParticleBuffers gpu,int current,int previous,int emitter,NativeStormSimulation.FrozenDisplay display){
        if(emitter<0)display=null;
        writePlayback=false;
        if(display==null&&identity==0){candidateSequence=candidateIdentity=0;return;}
        ensure();
        if(++stamp==0){clearMap();stamp=1;}
        candidateSequence=display==null?0:display.sequence();
        candidateIdentity=display==null?0:display.identity();
        candidateEpoch=gpu.identityEpoch();
        boolean reusable=candidateIdentity!=0&&candidateIdentity==identity&&candidateEpoch==resourceEpoch;
        boolean continuous=reusable&&candidateSequence>=sequence&&candidateSequence-sequence<=1;
        writePlayback=display!=null&&(!reusable||candidateSequence!=sequence);
        if(writePlayback){
            candidateWorldTick=display.gameTime();samePlaybackIdentity=reusable;
            transition=clock.transition(candidateSequence,candidateWorldTick,reusable);
            bind(13,playback[playbackBank]);bind(15,playback[1-playbackBank]);
            GpuUniforms.setUIntUniform(program,"uPlaybackPriorStamp",reusable?playbackStamp:0);
            GpuUniforms.setIntUniform(program,"uPlaybackContinuous",transition.continuous()?1:0);
            GpuUniforms.setIntUniform(program,"uPlaybackNewAnchor",transition.newAnchor()?1:0);
            var weights=transition.source();
            GpuUniforms.uniform4f(program,GpuUniforms.loc(program,"uPlaybackSource"),weights.oldest(),weights.previous(),weights.current(),weights.anchor());
        }
        GpuUniforms.setIntUniform(program,"uPlaybackWrite",writePlayback?1:0);
        var starfall=StarfallGpuBuffers.INSTANCE;
        GpuBindings.program(program);
        gpu.bindParticleRead(0);gpu.bindParticleWrite(1);
        gpu.bindCounter(ParticleBuffers.COUNTER_BB,current);
        gpu.bindPrevCounter(ParticleBuffers.PREVCOUNTER_BINDING,previous);
        gpu.bindMemberMap();gpu.bindIdentities(program);gpu.bindUpdateCapacity(program);
        gpu.bindEmitters(ParticleBuffers.EMITTER_BB);
        boolean poseReady=starfall.nativeProjectionReady();
        if(poseReady){
            starfall.bind(program,true);
            if(display!=null)starfall.nativeDisplayGameTime(display.gameTime());
        }
        GpuUniforms.setIntUniform(program,"uProjectionPoseReady",poseReady?1:0);
        GpuUniforms.setFloatUniform(program,"uProjectionPoseStep",reusable?
                (float)Math.clamp((candidateSequence-sequence)*.05,0,.25):0);
        bind(STORM_PROJECTION_INPUT_BINDING,display==null?0:display.buffer());
        bind(STORM_PROJECTION_DISPATCH_BINDING,args);bind(STORM_PROJECTION_MAP_BINDING,map);
        GpuUniforms.setUIntUniform(program,"uProjectionStamp",stamp);
        GpuUniforms.setUIntUniform(program,"uEmitter",Math.max(0,emitter));
        GpuUniforms.setIntUniform(program,"uProjectionPresent",display!=null&&emitter>=0?1:0);
        GpuUniforms.setIntUniform(program,"uProjectionReuse",reusable?1:0);
        GpuUniforms.setIntUniform(program,"uProjectionContinuous",continuous?1:0);
        pair("uProjectionSequence",candidateSequence);pair("uProjectionTick",display==null?0:display.tick());
        pair("uProjectionIdentity",candidateIdentity);
        pair("uProjectionShown",candidateIdentity==shownIdentity?shownSequence:0);
        gpu.bindDispatch();phase(0);gpu.dispatch(ParticleBuffers.STORM_DISPATCH);AllayAnimationGpu.barrier();
        if(display!=null&&emitter>=0){
            phase(1);GpuReplayCommands.dispatchCompute(1,1,1);
            GpuReplayCommands.memoryBarrier(GL43.GL_COMMAND_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            phase(2);GpuBindings.bind(GL43.GL_DISPATCH_INDIRECT_BUFFER,args);
            GpuReplayCommands.dispatchComputeIndirect(0);AllayAnimationGpu.barrier();
        }
        phase(3);gpu.bindDispatch();gpu.dispatch(ParticleBuffers.STORM_DISPATCH);AllayAnimationGpu.barrier();
    }
    private static void bind(int slot,int buffer){GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,slot,buffer);}
    private void phase(int phase){GpuUniforms.setUIntUniform(program,"uPhase",phase);}
    private void pair(String name,long value){
        GpuUniforms.uniform2ui(program,GpuUniforms.loc(program,name),(int)value,(int)(value>>>32));
    }
    void commit(NativeStormSimulation owner){
        if(writePlayback&&samePlaybackIdentity&&candidateSequence>sequence+1)
            ClientFrameCapture.INSTANCE.nativeDisplayGap(candidateSequence-sequence-1);
        sequence=candidateSequence;identity=candidateIdentity;resourceEpoch=candidateEpoch;
        if(writePlayback){
            playbackBank=1-playbackBank;playbackStamp=stamp;
            clock.commit(sequence,candidateWorldTick,samePlaybackIdentity,transition);
            writePlayback=false;
        }
        if(sequence==0)return;
        shownSequence=identity==shownIdentity?Math.max(shownSequence,sequence):sequence;
        shownIdentity=identity;
        owner.displayCommitted(shownIdentity,shownSequence);
    }
    @Override public void close(){
        GpuPrograms.delete(program);GpuBufferNames.delete(map);GpuBufferNames.delete(args);
        for(int i=0;i<playback.length;i++){
            com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(playbackViews[i]);playbackViews[i]=0;
            GpuBufferNames.delete(playback[i]);playback[i]=0;
        }
        playbackBank=playbackStamp=0;writePlayback=false;
        program=map=args=stamp=0;sequence=identity=resourceEpoch=candidateSequence=candidateIdentity=candidateEpoch=0;
        shownSequence=shownIdentity=0;
    }
}
