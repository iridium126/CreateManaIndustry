package com.iridium126.createmanaindustry.client.greattrick.sharedfate;

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
                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,read);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,next);
                GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,0,0,(long)stateCapacity*STATE_BYTES);
                GL15.glDeleteBuffers(read);GL15.glDeleteBuffers(write);
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
            if(debrisRead!=0){copy(debrisRead,next,(long)debrisCapacity*2048*64);GL15.glDeleteBuffers(debrisRead);GL15.glDeleteBuffers(debrisWrite);}
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
        staging.flip();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,input);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,staging);
        return true;
    }
    public void generate(ParticleAppendTarget output,int counterSlot,Matrix4fc view,Matrix4fc projection,float[] frustum,float throttle) {
        if(frames.isEmpty())return;
        int emitter=output.emitter(SPEC),alphaEmitter=output.emitter(ALPHA_SPEC),ribbonEmitter=output.emitter(RIBBON_SPEC);
        if(emitter<0||alphaEmitter<0||ribbonEmitter<0)return;
        int needed=Math.addExact(Math.min(Math.min(output.capacity(),ClientConfig.sameFateMaxParticles),2048*frames.size()),4*frames.size());
        if(nodeWriteCapacity<needed){nodeWrite=reallocate(nodeWrite,(long)needed*64);nodeWriteCapacity=needed;}
        if(!debrisSlots.isEmpty())copy(debrisRead,debrisWrite,(long)debrisCapacity*2048*64);
        // This late allocation happens after the engine's normal header upload.
        // Keygen and the draw passes must see its conjure size/style on this frame too.
        output.flushHeaders();
        // Preserve offscreen slots and only promote poses after the whole owner frame commits.
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,read);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,write);
        GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,0,0,(long)stateCapacity*STATE_BYTES);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,input);GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,jobs);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,2,read);GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,3,write);
        output.bind(counterSlot,4,5);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,6,nodeWrite);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,7,debrisRead);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,8,debrisWrite);
        GL20.glUseProgram(prepare);
        uint(prepare,"uCount",frames.size());
        scalar(prepare,"uTime",SharedFateClient.now());scalar(prepare,"uFadeDistance",ClientConfig.particleFadeDistance);
        matrix(prepare,"uView",view);matrix(prepare,"uProjection",projection);
        scalar(prepare,"uViewportHeight",net.minecraft.client.Minecraft.getInstance().getWindow().getHeight());
        GL20.glUniform4fv(uniforms.location(prepare,"uFrustum"),frustum);
        ParticleDiagnostics.INSTANCE.mark("fate_prepare");
        GL43.glDispatchCompute((frames.size()+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        GL20.glUseProgram(reserve);uint(reserve,"uCount",frames.size());uint(reserve,"uCapacity",output.capacity());
        uint(reserve,"uBudget",Math.max(0,(int)(ClientConfig.sameFateMaxParticles*Math.max(0,Math.min(1,throttle)))));
        ParticleDiagnostics.INSTANCE.mark("fate_reserve");
        GL43.glDispatchCompute(1,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        GL20.glUseProgram(emit);uint(emit,"uEmitter",emitter);uint(emit,"uRibbonAlphaEmitter",alphaEmitter);uint(emit,"uRibbonEmitter",ribbonEmitter);uint(emit,"uDebrisSlots",debrisCapacity);scalar(emit,"uTime",SharedFateClient.now());
        matrix(emit,"uView",view);matrix(emit,"uProjection",projection);
        scalar(emit,"uViewportHeight",net.minecraft.client.Minecraft.getInstance().getWindow().getHeight());
        ParticleDiagnostics.INSTANCE.mark("fate_emit");
        GL43.glDispatchCompute(frames.size(),1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        ParticleDiagnostics.INSTANCE.mark("fate_handoff");
        staged=true;
    }
    public void bindDrawInputs(){
        if(nodeRead==0)nodeRead=buffer(64);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,RIBBON_BINDING,nodeRead);
    }
    public void commit(){
        if(!staged)return;staged=false;int previous=read;read=write;write=previous;
        previous=nodeRead;nodeRead=nodeWrite;nodeWrite=previous;int oldCapacity=nodeReadCapacity;nodeReadCapacity=nodeWriteCapacity;nodeWriteCapacity=oldCapacity;
        if(!debrisSlots.isEmpty()){previous=debrisRead;debrisRead=debrisWrite;debrisWrite=previous;}
        frames.forEach(f->f.wire().lastCamera=camera);
    }
    public void reset(){staged=false;frames=List.of();debrisSlots.clear();if(read!=0)zero(read);if(write!=0)zero(write);if(debrisRead!=0)zero(debrisRead);if(debrisWrite!=0)zero(debrisWrite);}
    private static void copy(int source,int target,long bytes){
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,source);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,target);
        GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,0,0,bytes);
    }
    private void initialize(){if(prepare!=0)return;try{if(debrisRead==0){debrisRead=buffer(64);debrisWrite=buffer(64);}prepare=compile("shared_fate_prepare.comp");reserve=compile("shared_fate_reserve.comp");emit=compile("shared_fate_emit.comp");}catch(RuntimeException failure){close();throw failure;}}
    static int compile(String path){
        return com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler.compute(path,
                "#version 450 core\n" + SOURCES.apply(path));
    }
    private static int powerOfTwo(int count){if(count<=1)return 1;return Math.multiplyExact(Integer.highestOneBit(count-1),2);}
    private static int buffer(long bytes){
        if(bytes>GpuCapabilities.current().storageBlockBytes())throw new IllegalStateException("Shared-fate GPU metadata exceeds SSBO capacity");
        int buffer=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,bytes,GL15.GL_DYNAMIC_COPY);zero(buffer);return buffer;
    }
    private static void zero(int buffer){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_RGBA32F,GL11.GL_RGBA,GL11.GL_FLOAT,(ByteBuffer)null);}
    private static int reallocate(int old,long bytes){int next=buffer(bytes);if(old!=0)GL15.glDeleteBuffers(old);return next;}
    private void uint(int p,String name,int value){GL30.glUniform1ui(uniforms.location(p,name),value);}
    private void scalar(int p,String name,float value){GL20.glUniform1f(uniforms.location(p,name),value);}
    private void matrix(int p,String name,Matrix4fc value){uniforms.matrix(p,name,value);}
    public void close(){
        uniforms.clear();
        for(int buffer:new int[]{input,jobs,read,write,nodeRead,nodeWrite,debrisRead,debrisWrite})if(buffer!=0)GL15.glDeleteBuffers(buffer);
        if(prepare!=0)GL20.glDeleteProgram(prepare);if(reserve!=0)GL20.glDeleteProgram(reserve);if(emit!=0)GL20.glDeleteProgram(emit);
        nodeRead=nodeWrite=nodeReadCapacity=nodeWriteCapacity=debrisRead=debrisWrite=debrisCapacity=0;debrisSlots.clear();
        disabled=false;staged=false;input=jobs=read=write=prepare=reserve=emit=inputCapacity=stateCapacity=0;staging=null;frames=List.of();
    }
}
