package com.iridium126.createmanaindustry.client.greattrick.sharedfate;

import com.iridium126.createmanaindustry.client.gpu.state.GpuBindings;

import com.iridium126.createmanaindustry.client.gpu.GpuCapabilities;

import com.iridium126.createmanaindustry.client.particles.engine.*;

import com.iridium126.createmanaindustry.client.greattrick.SharedFateClient;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import com.iridium126.createmanaindustry.client.particles.emitter.*;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import java.nio.ByteBuffer;
import java.util.*;

/** Parallel curves, ordered reservations and parallel emission into the existing conjure/Iris draw path. */
public final class SharedFateGpu implements AutoCloseable {
    public static final SharedFateGpu INSTANCE=new SharedFateGpu();
    private static final java.util.function.Function<String,String> SOURCES=com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderResources.loader("createmanaindustry","shaders/particles/");
    private final com.iridium126.createmanaindustry.client.gpu.shader.GpuUniformCache uniforms = new com.iridium126.createmanaindustry.client.gpu.shader.GpuUniformCache();
    // Five input vec4s; twenty-four job/state vec4s include the frame, LOD history and 33-point arc table.
    public static final int INPUT_BYTES=80, JOB_BYTES=384, STATE_BYTES=384;
    private static final EmitterSpec SPEC=com.iridium126.createmanaindustry.client.greattrick.GreatTrickParticleSpecs.conjure();
    private static final EmitterSpec ALPHA_SPEC=com.iridium126.createmanaindustry.client.greattrick.GreatTrickParticleSpecs.ribbon(true);
    private static final EmitterSpec RIBBON_SPEC=com.iridium126.createmanaindustry.client.greattrick.GreatTrickParticleSpecs.ribbon(false);
    /** Draw-only reuse of the compute grid binding; it is restored before the next compute phase. */
    public static final int RIBBON_BINDING=13;
    private int input,jobs,read,write,prepare,reserve,emit,inputCapacity,stateCapacity;
    private ByteBuffer staging;
    private int nodeRead,nodeWrite,nodeWriteCapacity,nodeReadCapacity,debrisRead,debrisWrite,debrisCapacity;
    private int nodeReadDispatch,nodeWriteDispatch;
    private long nodeGeneration=-1,candidateGeneration=-1;
    private int displayProgram,displayNodes,displayCapacity;
    private boolean displayActive;
    private Vec3 currentCamera=Vec3.ZERO,previousCamera=Vec3.ZERO;
    private final Map<UUID,Integer> debrisSlots=new HashMap<>();
    private List<SharedFateClient.Frame> frames=List.of();
    private Vec3 camera=Vec3.ZERO;
    private boolean staged;
    private boolean disabled;
    public int count(){return frames.size();}
    public boolean prepare(Vec3 camera,float partial) {
        if(disabled)return false;
        try{return prepareFrame(camera,partial);}catch(RuntimeException failure){
            close();disabled=true;
            com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.error("Shared-fate GPU rendering disabled until reload",failure);
            return false;
        }
    }
    private boolean prepareFrame(Vec3 camera,float partial) {
        staged=false;
        frames=SharedFateClient.frames(camera,partial,ClientConfig.particleFadeDistance,
                Math.min(65535,Math.max(1,ClientConfig.sameFateMaxParticles/64)));
        this.camera=camera;
        if(frames.isEmpty())return false;
        initialize();
        int capacity=powerOfTwo(frames.size()),slots=powerOfTwo(Math.max(1,SharedFateClient.slots()));
        if(capacity>inputCapacity){
            input=reallocate(input,(long)capacity*INPUT_BYTES);jobs=reallocate(jobs,(long)capacity*JOB_BYTES);
            inputCapacity=capacity;staging=BufferUtils.createByteBuffer(capacity*INPUT_BYTES);
        }
        if(slots>stateCapacity) {
            int next=buffer((long)slots*STATE_BYTES),nextWrite=buffer((long)slots*STATE_BYTES);
            if(read!=0){
                GpuBindings.bind(GL31.GL_COPY_READ_BUFFER,read);GpuBindings.bind(GL31.GL_COPY_WRITE_BUFFER,next);
                com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.copyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,0,0,(long)stateCapacity*STATE_BYTES);
                com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(read);com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(write);
            }
            read=next;write=nextWrite;stateCapacity=slots;
        }
        Set<UUID> dissolving=new HashSet<>();
        frames.stream().filter(f->f.wire().stopped>=0).forEach(f->dissolving.add(f.wire().id));
        debrisSlots.keySet().retainAll(dissolving);
        int maximumSlots=Math.min(64,Math.max(1,ClientConfig.sameFateMaxParticles/2048));
        boolean[] occupied=new boolean[maximumSlots];
        debrisSlots.values().stream().filter(slot->slot<maximumSlots).forEach(slot->occupied[slot]=true);
        for(var frame:frames)if(frame.wire().stopped>=0&&!debrisSlots.containsKey(frame.wire().id)){
            for(int slot=0;slot<maximumSlots;slot++)if(!occupied[slot]){debrisSlots.put(frame.wire().id,slot);occupied[slot]=true;break;}
        }
        int needed=debrisSlots.values().stream().mapToInt(slot->slot+1).max().orElse(0);
        if(needed>debrisCapacity){
            int slotsNeeded=powerOfTwo(needed),next=buffer((long)slotsNeeded*2048*64),nextWrite=buffer((long)slotsNeeded*2048*64);
            if(debrisRead!=0){copy(debrisRead,next,(long)debrisCapacity*2048*64);com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(debrisRead);com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(debrisWrite);}
            debrisRead=next;debrisWrite=nextWrite;debrisCapacity=slotsNeeded;
        }
        staging.clear();
        for(var frame:frames) {
            var a=frame.a().position().subtract(camera);var b=frame.b().position().subtract(camera);var wire=frame.wire();
            staging.putFloat((float)a.x).putFloat((float)a.y).putFloat((float)a.z).putFloat(frame.a().height());
            staging.putFloat((float)b.x).putFloat((float)b.y).putFloat((float)b.z).putFloat(frame.b().height());
            staging.putFloat(wire.born).putFloat(wire.stopped).putFloat(wire.stopped>=0?1:0).putFloat(wire.seed);
            staging.putInt(wire.slot).putInt(wire.generation).putInt(wire.animate?1:0).putInt(debrisSlots.getOrDefault(wire.id,-1)+1);
            Vec3 delta=wire.lastCamera==null?Vec3.ZERO:wire.lastCamera.subtract(camera);
            staging.putFloat((float)delta.x).putFloat((float)delta.y).putFloat((float)delta.z).putFloat(0);
        }
        staging.flip();GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,input);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,0,staging);
        return true;
    }
    public void generate(ParticleAppendTarget output,int counterSlot,Matrix4fc view,Matrix4fc projection,float[] frustum,float throttle) {
        if(frames.isEmpty())return;
        int emitter=output.ensureEmitter(SPEC),alphaEmitter=output.ensureEmitter(ALPHA_SPEC),ribbonEmitter=output.ensureEmitter(RIBBON_SPEC);
        if(emitter<0||alphaEmitter<0||ribbonEmitter<0)return;
        int needed=Math.addExact(Math.min(Math.min(output.capacity(),ClientConfig.sameFateMaxParticles),2048*frames.size()),4*frames.size());
        if(nodeWriteCapacity<needed){nodeWrite=reallocate(nodeWrite,(long)needed*64);nodeWriteCapacity=needed;}
        if(nodeWriteDispatch==0)nodeWriteDispatch=buffer(16);
        if(!debrisSlots.isEmpty())copy(debrisRead,debrisWrite,(long)debrisCapacity*2048*64);
        // This late allocation happens after the engine's normal header upload.
        // Keygen and the draw passes must see its conjure size/style on this frame too.
        output.flushHeaders();
        // Preserve offscreen slots and only promote poses after the whole owner frame commits.
        GpuBindings.bind(GL31.GL_COPY_READ_BUFFER,read);GpuBindings.bind(GL31.GL_COPY_WRITE_BUFFER,write);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.copyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,0,0,(long)stateCapacity*STATE_BYTES);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,0,input);GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,1,jobs);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,2,read);GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,3,write);
        output.bind(output.reserveOutput(),counterSlot,4,5);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,6,nodeWrite);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,7,debrisRead);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,8,debrisWrite);
        com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(prepare);
        uint(prepare,"uCount",frames.size());
        scalar(prepare,"uTime",SharedFateClient.now());scalar(prepare,"uFadeDistance",ClientConfig.particleFadeDistance);
        matrix(prepare,"uView",view);matrix(prepare,"uProjection",projection);
        scalar(prepare,"uViewportHeight",net.minecraft.client.Minecraft.getInstance().getWindow().getHeight());
        GL20.glUniform4fv(uniforms.location(prepare,"uFrustum"),frustum);
        ParticleDiagnostics.INSTANCE.mark("fate_prepare");
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute((frames.size()+63)/64,1,1);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(reserve);uint(reserve,"uCount",frames.size());uint(reserve,"uCapacity",output.capacity());
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,9,nodeWriteDispatch);
        uint(reserve,"uSimulationGeneration",(int)output.historyGeneration());
        uint(reserve,"uBudget",Math.max(0,(int)(ClientConfig.sameFateMaxParticles*Math.max(0,Math.min(1,throttle)))));
        ParticleDiagnostics.INSTANCE.mark("fate_reserve");
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute(1,1,1);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(emit);uint(emit,"uEmitter",emitter);uint(emit,"uRibbonAlphaEmitter",alphaEmitter);uint(emit,"uRibbonEmitter",ribbonEmitter);uint(emit,"uDebrisSlots",debrisCapacity);scalar(emit,"uTime",SharedFateClient.now());
        output.bindHistory(emit);uint(emit,"uSimulationGeneration",(int)output.historyGeneration());
        matrix(emit,"uView",view);matrix(emit,"uProjection",projection);
        scalar(emit,"uViewportHeight",net.minecraft.client.Minecraft.getInstance().getWindow().getHeight());
        ParticleDiagnostics.INSTANCE.mark("fate_emit");
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute(frames.size(),1,1);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        ParticleDiagnostics.INSTANCE.mark("fate_handoff");
        candidateGeneration=output.historyGeneration();staged=true;
    }
    public void bindDrawInputs(){
        if(nodeRead==0)nodeRead=buffer(64);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,RIBBON_BINDING,displayActive?displayNodes:nodeRead);
    }
    public void clearDisplay(){displayActive=false;}
    public void publishDisplay(long generation,float alpha,Vec3 displayCamera){
        displayActive=false;if(nodeReadDispatch==0||nodeGeneration!=generation)return;
        if(displayProgram==0)displayProgram=compile("shared_fate_display.comp");
        if(displayCapacity<nodeReadCapacity){displayNodes=reallocate(displayNodes,(long)nodeReadCapacity*64);displayCapacity=nodeReadCapacity;}
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,0,nodeRead);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,1,nodeWrite==0?nodeRead:nodeWrite);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,2,displayNodes);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,3,nodeReadDispatch);
        com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(displayProgram);scalar(displayProgram,"uAlpha",alpha);
        Vec3 now=currentCamera.subtract(displayCamera),before=previousCamera.subtract(displayCamera);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current3f(uniforms.location(displayProgram,"uCurrentCameraDelta"),(float)now.x,(float)now.y,(float)now.z);
        com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current3f(uniforms.location(displayProgram,"uPreviousCameraDelta"),(float)before.x,(float)before.y,(float)before.z);
        GpuBindings.bind(GL43.GL_DISPATCH_INDIRECT_BUFFER,nodeReadDispatch);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_COMMAND_BARRIER_BIT);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchComputeIndirect(Integer.BYTES);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        displayActive=true;
    }
    public void commit(){
        if(!staged)return;staged=false;int previous=read;read=write;write=previous;
        previousCamera=currentCamera;currentCamera=camera;displayActive=false;
        previous=nodeRead;nodeRead=nodeWrite;nodeWrite=previous;int oldCapacity=nodeReadCapacity;nodeReadCapacity=nodeWriteCapacity;nodeWriteCapacity=oldCapacity;
        previous=nodeReadDispatch;nodeReadDispatch=nodeWriteDispatch;nodeWriteDispatch=previous;nodeGeneration=candidateGeneration;
        if(!debrisSlots.isEmpty()){previous=debrisRead;debrisRead=debrisWrite;debrisWrite=previous;}
        frames.forEach(f->f.wire().lastCamera=camera);
    }
    public void reset(){staged=false;displayActive=false;nodeGeneration=candidateGeneration=-1;currentCamera=previousCamera=Vec3.ZERO;frames=List.of();debrisSlots.clear();if(read!=0)zero(read);if(write!=0)zero(write);if(debrisRead!=0)zero(debrisRead);if(debrisWrite!=0)zero(debrisWrite);}
    private static void copy(int source,int target,long bytes){
        GpuBindings.bind(GL31.GL_COPY_READ_BUFFER,source);GpuBindings.bind(GL31.GL_COPY_WRITE_BUFFER,target);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.copyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,0,0,bytes);
    }
    private void initialize(){if(prepare!=0)return;try{if(debrisRead==0){debrisRead=buffer(64);debrisWrite=buffer(64);}prepare=compile("shared_fate_prepare.comp");reserve=com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler.compute(
            "shared_fate_reserve.comp","#version 450 core\n#define CMI_FATE_DISPLAY_DISPATCH\n"+SOURCES.apply("shared_fate_reserve.comp"));emit=compile("shared_fate_emit.comp");}catch(RuntimeException failure){close();throw failure;}}
    static int compile(String path){
        return com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler.compute(path,
                "#version 450 core\n" + SOURCES.apply(path));
    }
    private static int powerOfTwo(int count){if(count<=1)return 1;return Math.multiplyExact(Integer.highestOneBit(count-1),2);}
    private static int buffer(long bytes){
        if(bytes>GpuCapabilities.current().storageBlockBytes())throw new IllegalStateException("Shared-fate GPU metadata exceeds SSBO capacity");
        int buffer=GL15.glGenBuffers();GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,buffer);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.allocate(GL43.GL_SHADER_STORAGE_BUFFER,bytes,GL15.GL_DYNAMIC_COPY);zero(buffer);return buffer;
    }
    private static void zero(int buffer){GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,buffer);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.clearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_RGBA32F,GL11.GL_RGBA,GL11.GL_FLOAT,(ByteBuffer)null);}
    private static int reallocate(int old,long bytes){int next=buffer(bytes);if(old!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(old);return next;}
    private void uint(int p,String name,int value){com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current1ui(uniforms.location(p,name),value);}
    private void scalar(int p,String name,float value){com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.current1f(uniforms.location(p,name),value);}
    private void matrix(int p,String name,Matrix4fc value){uniforms.matrix(p,name,value);}
    public void close(){
        uniforms.clear();
        for(int buffer:new int[]{input,jobs,read,write,nodeRead,nodeWrite,nodeReadDispatch,nodeWriteDispatch,debrisRead,debrisWrite,displayNodes})if(buffer!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(buffer);
        if(displayProgram!=0)com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms.delete(displayProgram);
        displayProgram=displayNodes=displayCapacity=0;displayActive=false;
        if(prepare!=0)com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms.delete(prepare);if(reserve!=0)com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms.delete(reserve);if(emit!=0)com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms.delete(emit);
        nodeRead=nodeWrite=nodeReadCapacity=nodeWriteCapacity=debrisRead=debrisWrite=debrisCapacity=0;debrisSlots.clear();
        nodeReadDispatch=nodeWriteDispatch=0;nodeGeneration=candidateGeneration=-1;
        disabled=false;staged=false;input=jobs=read=write=prepare=reserve=emit=inputCapacity=stateCapacity=0;staging=null;frames=List.of();
    }
}
