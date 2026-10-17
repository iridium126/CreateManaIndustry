package com.iridium126.createmanaindustry.client.greattrick.partition;

import com.iridium126.createmanaindustry.client.particles.engine.*;

import com.iridium126.createmanaindustry.client.greattrick.PartitionClient;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import com.iridium126.createmanaindustry.client.particles.emitter.EmitterSpec;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import java.nio.ByteBuffer;
import java.util.*;

/** One batch of analytic faces/edges, one shared conjure material, no CPU particles or GPU readback. */
public final class PartitionGpu implements AutoCloseable {
    public static final PartitionGpu INSTANCE=new PartitionGpu();
    private static final java.util.function.Function<String,String> SOURCES=com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderResources.loader("createmanaindustry","shaders/particles/");
    private final com.iridium126.createmanaindustry.client.gpu.shader.GpuUniformCache uniforms = new com.iridium126.createmanaindustry.client.gpu.shader.GpuUniformCache();
    private static final EmitterSpec SPEC=com.iridium126.createmanaindustry.client.greattrick.GreatTrickParticleSpecs.conjure();
    private int input,jobs,capacity,prepare,reserve,emit;
    private ByteBuffer staging;
    private List<PartitionClient.Field> fields=List.of();
    private final Map<UUID,Vec3> frozen=new HashMap<>();
    private boolean disabled;
    public boolean prepare(Vec3 camera) {
        if(disabled||ClientConfig.partitionMaxParticles<=0)return false;
        try {
            fields=PartitionClient.frames(camera,ClientConfig.particleFadeDistance).stream().limit(2048).toList();
            frozen.keySet().retainAll(fields.stream().map(f->f.id).collect(java.util.stream.Collectors.toSet()));
            if(fields.isEmpty())return false;
            if(prepare==0){prepare=compile("partition_prepare.comp");reserve=compile("partition_reserve.comp");emit=compile("partition_emit.comp");}
            if(fields.size()>capacity) {
                capacity=Integer.highestOneBit(fields.size())*2;
                if(input!=0){com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(input);com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(jobs);}
                input=buffer((long)capacity*64);jobs=buffer((long)capacity*18*64);staging=BufferUtils.createByteBuffer(capacity*64);
            }
            staging.clear();float time=PartitionClient.now();
            for(var f:fields) {
                var b=f.bounds;
                staging.putFloat((float)(b.minX()-camera.x)).putFloat((float)(b.minY()-camera.y)).putFloat((float)(b.minZ()-camera.z)).putFloat(0);
                staging.putFloat((float)(b.high(0)-camera.x)).putFloat((float)(b.high(1)-camera.y)).putFloat((float)(b.high(2)-camera.z)).putFloat(0);
                staging.putFloat(time-f.born).putFloat(f.stopped<0?-1:time-f.stopped).putFloat(f.animate?1:0).putFloat(f.id.hashCode()&65535);
                Vec3 anchor=f.stopped<0?Vec3.ZERO:frozen.computeIfAbsent(f.id,k->camera).subtract(camera);
                staging.putFloat((float)anchor.x).putFloat((float)anchor.y).putFloat((float)anchor.z).putFloat(0);
            }
            staging.flip();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,input);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,0,staging);return true;
        }catch(RuntimeException error){close();disabled=true;com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.error("Partition GPU rendering disabled until reload",error);return false;}
    }
    public void generate(ParticleAppendTarget output,int slot,org.joml.Matrix4fc view,org.joml.Matrix4fc projection,float[] frustum,float throttle) {
        if(fields.isEmpty())return;
        int emitter=output.ensureEmitter(SPEC);if(emitter<0)return;output.flushHeaders();
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,input);GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,jobs);
        output.bind(output.reserveOutput(),slot,4,5);
        GL20.glUseProgram(prepare);uint(prepare,"uCount",fields.size());scalar(prepare,"uFadeDistance",ClientConfig.particleFadeDistance);
        GL20.glUniform4fv(uniforms.location(prepare,"uFrustum"),frustum);
        GL43.glDispatchCompute((fields.size()*18+63)/64,1,1);barrier();
        GL20.glUseProgram(reserve);uint(reserve,"uCount",fields.size());uint(reserve,"uCapacity",output.capacity());
        uint(reserve,"uBudget",(int)(ClientConfig.partitionMaxParticles*Math.max(0,Math.min(1,throttle))));
        GL43.glDispatchCompute(1,1,1);barrier();
        GL20.glUseProgram(emit);uint(emit,"uEmitter",emitter);scalar(emit,"uFadeDistance",ClientConfig.particleFadeDistance);
        uniforms.matrix(emit,"uView",view);
        uniforms.matrix(emit,"uProjection",projection);
        scalar(emit,"uViewportHeight",net.minecraft.client.Minecraft.getInstance().getWindow().getHeight());
        GL43.glDispatchCompute(fields.size()*18,1,1);barrier();
    }
    private static int compile(String path){return com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler.compute(path,"#version 450 core\n"+SOURCES.apply(path));}
    private static void barrier(){GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);}
    private void uint(int p,String n,int v){GL30.glUniform1ui(uniforms.location(p,n),v);}
    private void scalar(int p,String n,float v){GL20.glUniform1f(uniforms.location(p,n),v);}
    private static int buffer(long size){int b=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,b);com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers.allocate(GL43.GL_SHADER_STORAGE_BUFFER,size,GL15.GL_DYNAMIC_COPY);return b;}
    public void reset(){fields=List.of();frozen.clear();}
    public void close(){uniforms.clear();if(input!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(input);if(jobs!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(jobs);for(int p:new int[]{prepare,reserve,emit})if(p!=0)com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms.delete(p);input=jobs=prepare=reserve=emit=capacity=0;staging=null;disabled=false;reset();}
}
