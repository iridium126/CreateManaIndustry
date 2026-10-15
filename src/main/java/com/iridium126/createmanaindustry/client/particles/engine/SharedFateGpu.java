package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.greattrick.SharedFateClient;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import com.iridium126.createmanaindustry.client.particles.emitter.*;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import java.nio.ByteBuffer;
import java.util.List;

/** Parallel curves, ordered reservations and parallel emission into the existing conjure/Iris draw path. */
public final class SharedFateGpu implements AutoCloseable {
    private static final EmitterSpec SPEC=sharedSpec();
    static EmitterSpec sharedSpec(){
        var spec=EmitterSpec.builder().shape(EmitterShape.POINT).speed(0,0).life(1,1)
                .sizeOverLife(1,1,1).colors(new float[]{1,1,1,1}).glow(1)
                .type(ParticleTypes.standard(ParticleTypes.Material.ADDITIVE)).build();
        // Tag before insertion into the engine's value-keyed emitter cache.
        spec.packed()[HexSpecs.HDR_COLOR_MODE]=5;spec.packed()[HexSpecs.HDR_SPAWN_STYLE]=8;
        return spec;
    }
    private int input,jobs,read,write,prepare,reserve,emit,inputCapacity,stateCapacity;
    private ByteBuffer staging;
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
                Math.min(65535,Math.max(1,ClientConfig.sameFateMaxParticles/16)));
        this.camera=camera;
        if(frames.isEmpty())return false;
        initialize();
        int capacity=powerOfTwo(frames.size()),slots=powerOfTwo(Math.max(1,SharedFateClient.slots()));
        if(capacity>inputCapacity){
            input=reallocate(input,(long)capacity*80);jobs=reallocate(jobs,(long)capacity*80);
            inputCapacity=capacity;staging=BufferUtils.createByteBuffer(capacity*80);
        }
        if(slots>stateCapacity) {
            int next=buffer((long)slots*80),nextWrite=buffer((long)slots*80);
            if(read!=0){
                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,read);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,next);
                GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,0,0,(long)stateCapacity*80);
                GL15.glDeleteBuffers(read);GL15.glDeleteBuffers(write);
            }
            read=next;write=nextWrite;stateCapacity=slots;
        }
        staging.clear();
        for(var frame:frames) {
            var a=frame.a().position().subtract(camera);var b=frame.b().position().subtract(camera);var wire=frame.wire();
            staging.putFloat((float)a.x).putFloat((float)a.y).putFloat((float)a.z).putFloat(frame.a().height());
            staging.putFloat((float)b.x).putFloat((float)b.y).putFloat((float)b.z).putFloat(frame.b().height());
            staging.putFloat(wire.born).putFloat(wire.stopped).putFloat(wire.stopped>=0?1:0).putFloat(wire.seed);
            staging.putInt(wire.slot).putInt(wire.generation).putInt(wire.animate?1:0).putInt(0);
            Vec3 delta=wire.lastCamera==null?Vec3.ZERO:wire.lastCamera.subtract(camera);
            staging.putFloat((float)delta.x).putFloat((float)delta.y).putFloat((float)delta.z).putFloat(0);
        }
        staging.flip();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,input);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,staging);
        return true;
    }
    public void generate(ParticleBuffers pool,CMIParticleEngine engine,int counterSlot,Matrix4fc view,Matrix4fc projection,float[] frustum,float throttle) {
        if(frames.isEmpty())return;
        int emitter=engine.ensureEmitter(SPEC);if(emitter<0)return;
        // This late allocation happens after the engine's normal header upload.
        // Keygen and the draw passes must see its conjure size/style on this frame too.
        pool.uploadDirtyEmitters();
        // Preserve offscreen slots and only promote poses after the whole owner frame commits.
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,read);GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,write);
        GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,0,0,(long)stateCapacity*80);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,input);GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,jobs);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,2,read);GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,3,write);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,4,pool.counterBufferId(counterSlot));
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,5,pool.particleWriteBufferId());
        GL20.glUseProgram(prepare);
        uint(prepare,"uCount",frames.size());
        scalar(prepare,"uTime",SharedFateClient.now());scalar(prepare,"uFadeDistance",ClientConfig.particleFadeDistance);
        matrix(prepare,"uView",view);matrix(prepare,"uProjection",projection);
        scalar(prepare,"uViewportHeight",net.minecraft.client.Minecraft.getInstance().getWindow().getHeight());
        GL20.glUniform4fv(GL20.glGetUniformLocation(prepare,"uFrustum"),frustum);
        GL43.glDispatchCompute((frames.size()+63)/64,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        GL20.glUseProgram(reserve);uint(reserve,"uCount",frames.size());uint(reserve,"uCapacity",pool.capacity());
        uint(reserve,"uBudget",Math.max(0,(int)(ClientConfig.sameFateMaxParticles*Math.max(0,Math.min(1,throttle)))));
        GL43.glDispatchCompute(1,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        GL20.glUseProgram(emit);uint(emit,"uEmitter",emitter);scalar(emit,"uTime",SharedFateClient.now());
        matrix(emit,"uView",view);matrix(emit,"uProjection",projection);
        scalar(emit,"uViewportHeight",net.minecraft.client.Minecraft.getInstance().getWindow().getHeight());
        GL43.glDispatchCompute(frames.size(),1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        staged=true;
    }
    public void commit(){if(!staged)return;staged=false;int previous=read;read=write;write=previous;frames.forEach(f->f.wire().lastCamera=camera);}
    public void reset(){staged=false;frames=List.of();if(read!=0)zero(read);if(write!=0)zero(write);}
    private void initialize(){if(prepare!=0)return;try{prepare=compile("shared_fate_prepare.comp");reserve=compile("shared_fate_reserve.comp");emit=compile("shared_fate_emit.comp");}catch(RuntimeException failure){close();throw failure;}}
    static int compile(String path){
        int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER),program=0;
        try{
            GL20.glShaderSource(shader,"#version 450 core\n"+ParticlePrograms.loadParticlePlain(path));GL20.glCompileShader(shader);
            if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)throw new IllegalStateException(path+": "+GL20.glGetShaderInfoLog(shader));
            program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);GL20.glLinkProgram(program);
            if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
            return program;
        }catch(RuntimeException error){if(program!=0)GL20.glDeleteProgram(program);throw error;}finally{GL20.glDeleteShader(shader);}
    }
    private static int powerOfTwo(int count){if(count<=1)return 1;return Math.multiplyExact(Integer.highestOneBit(count-1),2);}
    private static int buffer(long bytes){
        if(bytes>GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE))throw new IllegalStateException("Shared-fate GPU metadata exceeds SSBO capacity");
        int buffer=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,bytes,GL15.GL_DYNAMIC_COPY);zero(buffer);return buffer;
    }
    private static void zero(int buffer){GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_RGBA32F,GL11.GL_RGBA,GL11.GL_FLOAT,(ByteBuffer)null);}
    private static int reallocate(int old,long bytes){int next=buffer(bytes);if(old!=0)GL15.glDeleteBuffers(old);return next;}
    private static void uint(int p,String name,int value){GL30.glUniform1ui(GL20.glGetUniformLocation(p,name),value);}
    private static void scalar(int p,String name,float value){GL20.glUniform1f(GL20.glGetUniformLocation(p,name),value);}
    private static void matrix(int p,String name,Matrix4fc value){GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(p,name),false,value.get(new float[16]));}
    public void close(){
        for(int buffer:new int[]{input,jobs,read,write})if(buffer!=0)GL15.glDeleteBuffers(buffer);
        if(prepare!=0)GL20.glDeleteProgram(prepare);if(reserve!=0)GL20.glDeleteProgram(reserve);if(emit!=0)GL20.glDeleteProgram(emit);
        disabled=false;staged=false;input=jobs=read=write=prepare=reserve=emit=inputCapacity=stateCapacity=0;staging=null;frames=List.of();
    }
}
