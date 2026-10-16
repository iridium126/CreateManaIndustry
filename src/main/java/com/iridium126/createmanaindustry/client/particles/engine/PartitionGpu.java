package com.iridium126.createmanaindustry.client.particles.engine;

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
    private static final EmitterSpec SPEC=SharedFateGpu.sharedSpec();
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
            if(prepare==0){prepare=SharedFateGpu.compile("partition_prepare.comp");reserve=SharedFateGpu.compile("partition_reserve.comp");emit=SharedFateGpu.compile("partition_emit.comp");}
            if(fields.size()>capacity) {
                capacity=Integer.highestOneBit(fields.size())*2;
                if(input!=0){GL15.glDeleteBuffers(input);GL15.glDeleteBuffers(jobs);}
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
            staging.flip();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,input);GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,staging);return true;
        }catch(RuntimeException error){close();disabled=true;com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.error("Partition GPU rendering disabled until reload",error);return false;}
    }
    public void generate(ParticleBuffers pool,CMIParticleEngine engine,int slot,org.joml.Matrix4fc view,org.joml.Matrix4fc projection,float[] frustum,float throttle) {
        if(fields.isEmpty())return;
        int emitter=engine.ensureEmitter(SPEC);if(emitter<0)return;pool.uploadDirtyEmitters();
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,input);GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,jobs);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,4,pool.counterBufferId(slot));GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,5,pool.particleWriteBufferId());
        GL20.glUseProgram(prepare);uint(prepare,"uCount",fields.size());scalar(prepare,"uFadeDistance",ClientConfig.particleFadeDistance);
        GL20.glUniform4fv(GL20.glGetUniformLocation(prepare,"uFrustum"),frustum);
        GL43.glDispatchCompute((fields.size()*18+63)/64,1,1);barrier();
        GL20.glUseProgram(reserve);uint(reserve,"uCount",fields.size());uint(reserve,"uCapacity",pool.capacity());
        uint(reserve,"uBudget",(int)(ClientConfig.partitionMaxParticles*Math.max(0,Math.min(1,throttle))));
        GL43.glDispatchCompute(1,1,1);barrier();
        GL20.glUseProgram(emit);uint(emit,"uEmitter",emitter);scalar(emit,"uFadeDistance",ClientConfig.particleFadeDistance);
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(emit,"uView"),false,view.get(new float[16]));
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(emit,"uProjection"),false,projection.get(new float[16]));
        scalar(emit,"uViewportHeight",net.minecraft.client.Minecraft.getInstance().getWindow().getHeight());
        GL43.glDispatchCompute(fields.size()*18,1,1);barrier();
    }
    private static void barrier(){GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);}
    private static void uint(int p,String n,int v){GL30.glUniform1ui(GL20.glGetUniformLocation(p,n),v);}
    private static void scalar(int p,String n,float v){GL20.glUniform1f(GL20.glGetUniformLocation(p,n),v);}
    private static int buffer(long size){int b=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,b);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,size,GL15.GL_DYNAMIC_COPY);return b;}
    public void reset(){fields=List.of();frozen.clear();}
    public void close(){if(input!=0)GL15.glDeleteBuffers(input);if(jobs!=0)GL15.glDeleteBuffers(jobs);for(int p:new int[]{prepare,reserve,emit})if(p!=0)GL20.glDeleteProgram(p);input=jobs=prepare=reserve=emit=capacity=0;staging=null;disabled=false;reset();}
}
