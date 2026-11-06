package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import com.iridium126.createmanaindustry.client.gpu.state.GpuBindings;

import com.iridium126.createmanaindustry.client.allaystorm.*;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames;
import com.iridium126.createmanaindustry.client.gpu.shader.*;
import com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands;

/** GL Storm steering uses a stable publication and a portable LBVH. */
public final class SpatialScene implements AutoCloseable {
    public static final int CAPACITY=131072,PACKET_BYTES=com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.SPATIAL_HEADER_BYTES
            +(CAPACITY+SpatialTerrain.CAPACITY)*com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.SPATIAL_RECORD_BYTES;
    private static final AtomicLong EPOCHS=new AtomicLong(1);
    private final long epoch=EPOCHS.getAndIncrement();
    private long scene=EPOCHS.getAndIncrement(),nonce,terrainRevision=-1,resourceEpoch=Long.MIN_VALUE;
    private Object identity;
    private int program,meta,metaTexture,input,output,outputTexture;
    private final int[] states=new int[2],textures=new int[2];
    private int read;
    private final int probes=2;
    private final SpatialTerrain terrain=new SpatialTerrain(SpatialTerrain.COLLISION);
    private GlSpatialIndex glIndex;
    private long glQueries;
    private int hintsMembers;
    private long hintsNonce;
    private boolean pending;
    public SpatialScene(){
        try{
        program=GpuShaderCompiler.compute("motion publication","#version 450 core\n"+ParticlePrograms.commonPrelude()+ParticlePrograms.loadParticlePlain("spatial/motion_publication.comp"));
        GpuProgramContract.verify(program,"spatial/motion_publication.comp");
        ui(program,"capacity",CAPACITY);ui(program,"probes",probes);ui(program,"kind",0);
        for(int i=0;i<2;i++){states[i]=buffer(CAPACITY*64L);textures[i]=texture(states[i]);GpuReplayCommands.clearNamedBufferData(states[i],GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);}
        meta=buffer(CAPACITY*16L);metaTexture=texture(meta);
        input=buffer(PACKET_BYTES);output=buffer(PACKET_BYTES);outputTexture=texture(output);
        glIndex=new GlSpatialIndex(CAPACITY,SpatialTerrain.CAPACITY);
        }catch(RuntimeException|LinkageError failure){close();throw failure;}
    }
    private static int buffer(long n){int b=GL45.glCreateBuffers();GL45.glNamedBufferData(b,n,GL15.GL_DYNAMIC_COPY);if(GL45.glGetNamedBufferParameteri64(b,GL15.GL_BUFFER_SIZE)!=n){GpuBufferNames.delete(b);throw new IllegalStateException("Motion state allocation failed");}return b;}
    private static int texture(int b){int t=GL45.glCreateTextures(GL31.GL_TEXTURE_BUFFER);GL45.glTextureBuffer(t,GL30.GL_RGBA32UI,b);return t;}
    private static void bind(int at,int b){GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,at,b);}
    private static void pair(int p,String name,long v){com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform2ui(p,GpuUniforms.loc(p,name),(int)v,(int)(v>>>32));}
    private static void ui(int p,String name,int v){com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(p,GpuUniforms.loc(p,name),v);}
    private static void flag(int p,String name,boolean v){com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(p,GpuUniforms.loc(p,name),v?1:0);}
    private void images(){GpuBindings.image(4,textures[1-read],0,false,0,GL15.GL_READ_WRITE,GL30.GL_RGBA32UI);GpuBindings.image(5,metaTexture,0,false,0,GL15.GL_READ_WRITE,GL30.GL_RGBA32UI);GpuBindings.image(7,textures[read],0,false,0,GL15.GL_READ_ONLY,GL30.GL_RGBA32UI);}
    private void phase(int n,int count){ui(program,"phase",n);com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(program);GpuReplayCommands.dispatchCompute(Math.max(1,(count+255)/256),1,1);AllayAnimationGpu.barrier();}
    private void packet(int target,long sourceTick,long nonce,float time){
        images();bind(0,target);bind(5,terrain.buffer());ui(program,"terrainCount",terrain.count());
        pair(program,"nonce",nonce);pair(program,"epoch",epoch);pair(program,"sourceTick",sourceTick);pair(program,"scene",scene);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1f(program,GpuUniforms.loc(program,"timeSec"),time);
        int members=memberRange();ui(program,"memberRange",members);
        phase(1,members+terrain.count());
    }
    private int memberRange(){return Math.clamp(AllayStormRuntime.INSTANCE.generatedMemberCount(),0,CAPACITY);}
    public void begin(long tick,float time,long nextResourceEpoch){
        if(pending)throw new IllegalStateException("Uncommitted motion tick");Object next=AllayStormRuntime.INSTANCE.identity();
        // A pool reset can reuse every member token without changing the Storm identity.
        // Never restore motion or consume native hints across that resource boundary.
        if(!java.util.Objects.equals(next,identity)||resourceEpoch!=nextResourceEpoch){identity=next;resourceEpoch=nextResourceEpoch;scene=EPOCHS.getAndIncrement();for(int b:states)GpuReplayCommands.clearNamedBufferData(b,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);AllayAnimationGpu.barrier();}
        terrain.refresh();terrainRevision=terrain.revision();
        glQueries++;
        hintsMembers=memberRange();
        hintsNonce=++nonce;packet(input,tick-1,hintsNonce,time-.05f);var center=AllayStormRuntime.INSTANCE.center();
        glIndex.queryRange(input,output,hintsNonce,epoch,tick-1,scene,probes,0,
                (float)center.x,(float)center.y,(float)center.z,hintsMembers,terrain.count());
        int members=memberRange();images();ui(program,"memberRange",members);phase(0,members);pending=true;
    }
    public void bindUpdate(int update,long tick){
        images();GpuBindings.image(6,outputTexture,0,false,0,GL15.GL_READ_ONLY,GL30.GL_RGBA32UI);
        flag(update,"uSpatialEnabled",true);flag(update,"uSpatialHintsEnabled",true);ui(update,"uSpatialHintMembers",hintsMembers);pair(update,"uSpatialEpoch",epoch);pair(update,"uSpatialScene",scene);pair(update,"uSpatialTick",tick);pair(update,"uSpatialNonce",hintsNonce);
    }
    public void publish(int pool,int counter,ParticleBuffers gpu,long generation){
        int members=memberRange();images();bind(1,pool);bind(3,counter);gpu.bindPopulationRead(program,false,generation);StarfallGpuBuffers.INSTANCE.bind(program,false);flag(program,"stateEnabled",StarfallGpuBuffers.INSTANCE.committedBuffer()!=0);ui(program,"memberRange",members);
        phase(2,members);
        read=1-read;pending=false;
    }
    public void abort(){pending=false;}
    public String description(){return "GL LBVH motion=20Hz probes="+probes+" gl_queries="+glQueries+" terrain_revision="+terrainRevision;}
    @Override public void close(){abort();if(glIndex!=null)glIndex.close();glIndex=null;terrain.close();for(int t:textures)GpuObjectNames.deleteTexture(t);GpuObjectNames.deleteTexture(metaTexture);GpuObjectNames.deleteTexture(outputTexture);for(int b:states)GpuBufferNames.delete(b);for(int b:new int[]{meta,input,output})GpuBufferNames.delete(b);GpuPrograms.delete(program);}
}
