package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import com.iridium126.createmanaindustry.client.allaystorm.*;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames;
import com.iridium126.createmanaindustry.client.gpu.shader.*;
import com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands;

/** Two stable member banks; three native snapshots never alias the live simulation. */
public final class SpatialScene implements AutoCloseable {
    public static final int CAPACITY=131072,PACKET_BYTES=128+(CAPACITY+SpatialTerrain.CAPACITY)*64;
    private static final AtomicLong EPOCHS=new AtomicLong(1);
    private final long epoch=EPOCHS.getAndIncrement();
    private long scene=EPOCHS.getAndIncrement(),nonce,terrainRevision=-1,resourceEpoch=Long.MIN_VALUE;
    private Object identity;
    private int program,meta,metaTexture,scratch,input,output,outputTexture,grid;
    private final int[] states=new int[2],textures=new int[2];
    private int read;
    private final int probes=Boolean.getBoolean("cmi.particle.wideBudget")?4:2,visits=Boolean.getBoolean("cmi.particle.wideBudget")?64:32,nearest=Boolean.getBoolean("cmi.particle.wideBudget")?8:4;
    private final SpatialTerrain terrain=new SpatialTerrain();
    private GlSpatialIndex glIndex;
    private RtQueryWorker rt;
    private final boolean nativeSelected;
    private final Path modules;
    private boolean nativeStartPending;
    private String nativeFailure="";
    private long glQueries;
    private RtQueryWorker.Slot accepted;
    private int hintsTexture;
    private int hintsMembers;
    private long hintsNonce;
    private boolean pending;
    public SpatialScene(Path modules,boolean nativeQueries){
        nativeSelected=nativeQueries;this.modules=modules;
        try{
        program=GpuShaderCompiler.compute("stable motion and local audit","#version 450 core\n"+ParticlePrograms.commonPrelude()+ParticlePrograms.loadParticlePlain("spatial/motion.comp"));
        for(int i=0;i<2;i++){states[i]=buffer(CAPACITY*64L);textures[i]=texture(states[i]);GpuReplayCommands.clearNamedBufferData(states[i],GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);}
        meta=buffer(CAPACITY*16L);metaTexture=texture(meta);scratch=buffer(CAPACITY*64L);input=buffer(PACKET_BYTES);output=buffer(PACKET_BYTES);outputTexture=texture(output);grid=buffer((262144L+CAPACITY)*4);
        if(!nativeQueries)glIndex=new GlSpatialIndex(CAPACITY,SpatialTerrain.CAPACITY);
        if(nativeQueries)startNative();
        }catch(RuntimeException|LinkageError failure){close();throw failure;}
    }
    private void startNative(){
        nativeStartPending=RtQueryWorker.resourcesRetiring();if(nativeStartPending)return;
        try{rt=new RtQueryWorker(CAPACITY,SpatialTerrain.CAPACITY,probes,epoch,modules);}
        catch(RuntimeException|LinkageError failure){nativeFailure=failure.toString();com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.warn("[CMI particles] RT unavailable; select GL explicitly to change backend",failure);}
    }
    private static int buffer(long n){int b=GL45.glCreateBuffers();GL45.glNamedBufferData(b,n,GL15.GL_DYNAMIC_COPY);if(GL45.glGetNamedBufferParameteri64(b,GL15.GL_BUFFER_SIZE)!=n){GpuBufferNames.delete(b);throw new IllegalStateException("Motion state allocation failed");}return b;}
    private static int texture(int b){int t=GL45.glCreateTextures(GL31.GL_TEXTURE_BUFFER);GL45.glTextureBuffer(t,GL30.GL_RGBA32UI,b);return t;}
    private static void bind(int at,int b){GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,at,b);}
    private static void pair(int p,String name,long v){GL41.glProgramUniform2ui(p,GpuUniforms.loc(p,name),(int)v,(int)(v>>>32));}
    private static void ui(int p,String name,int v){GL41.glProgramUniform1ui(p,GpuUniforms.loc(p,name),v);}
    private static void flag(int p,String name,boolean v){GL41.glProgramUniform1i(p,GpuUniforms.loc(p,name),v?1:0);}
    private void images(){GL42.glBindImageTexture(4,textures[1-read],0,false,0,GL15.GL_READ_WRITE,GL30.GL_RGBA32UI);GL42.glBindImageTexture(5,metaTexture,0,false,0,GL15.GL_READ_WRITE,GL30.GL_RGBA32UI);GL42.glBindImageTexture(7,textures[read],0,false,0,GL15.GL_READ_ONLY,GL30.GL_RGBA32UI);}
    private void phase(int n,int count){ui(program,"phase",n);GL20.glUseProgram(program);GpuReplayCommands.dispatchCompute(Math.max(1,(count+255)/256),1,1);AllayAnimationGpu.barrier();}
    private void packet(int target,long sourceTick,long nonce,float time,boolean current){
        images();bind(0,target);bind(5,terrain.buffer());ui(program,"capacity",CAPACITY);ui(program,"terrainCapacity",SpatialTerrain.CAPACITY);ui(program,"terrainCount",terrain.count());ui(program,"probes",probes);ui(program,"kind",0);
        pair(program,"nonce",nonce);pair(program,"epoch",epoch);pair(program,"sourceTick",sourceTick);pair(program,"scene",scene);GL41.glProgramUniform1f(program,GpuUniforms.loc(program,"timeSec"),time);
        int members=memberRange();ui(program,"memberRange",members);
        phase(current?3:1,members+terrain.count());
    }
    private int memberRange(){return nativeSelected?Math.clamp(AllayStormRuntime.INSTANCE.generatedMemberCount(),0,CAPACITY):CAPACITY;}
    public void begin(long tick,float time,long nextResourceEpoch){
        if(pending)throw new IllegalStateException("Uncommitted motion tick");if(nativeStartPending)startNative();Object next=AllayStormRuntime.INSTANCE.identity();
        // A pool reset can reuse every member token without changing the Storm identity.
        // Never restore motion or consume native hints across that resource boundary.
        if(!java.util.Objects.equals(next,identity)||resourceEpoch!=nextResourceEpoch){identity=next;resourceEpoch=nextResourceEpoch;scene=EPOCHS.getAndIncrement();for(int b:states)GpuReplayCommands.clearNamedBufferData(b,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(IntBuffer)null);AllayAnimationGpu.barrier();}
        terrain.refresh();terrainRevision=terrain.revision();
        if(rt!=null&&GpuReplayCommands.active())ParticleChainCapture.INSTANCE.invalidate("CUDA work is external to the GL journal; use the GL backend for replay, full client capture for RT cost");
        try{accepted=rt==null?null:rt.take(tick,scene,0);}catch(RuntimeException|LinkageError failure){disableNative(failure);accepted=null;}
        hintsTexture=0;
        if(accepted!=null){hintsTexture=accepted.texture;hintsNonce=accepted.nonce;hintsMembers=accepted.members;}else if(!nativeSelected){
            glQueries++;
            hintsMembers=CAPACITY;
            hintsNonce=++nonce;packet(input,tick-1,hintsNonce,time-.05f,false);var center=AllayStormRuntime.INSTANCE.center();glIndex.query(input,output,hintsNonce,epoch,tick-1,scene,probes,0,(float)center.x,(float)center.y,(float)center.z);hintsTexture=outputTexture;
        }
        images();ui(program,"capacity",CAPACITY);phase(0,CAPACITY);pending=true;
    }
    public void bindUpdate(int update,long tick){
        images();GL42.glBindImageTexture(6,hintsTexture,0,false,0,GL15.GL_READ_ONLY,GL30.GL_RGBA32UI);
        flag(update,"uSpatialEnabled",true);flag(update,"uSpatialHintsEnabled",hintsTexture!=0);ui(update,"uSpatialHintMembers",hintsMembers);pair(update,"uSpatialEpoch",epoch);pair(update,"uSpatialScene",scene);pair(update,"uSpatialTick",tick);pair(update,"uSpatialNonce",hintsNonce);
    }
    public void correct(int pool,int counter){
        images();bind(1,pool);bind(3,counter);bind(6,states[1-read]);bind(7,scratch);bind(9,grid);ui(program,"capacity",CAPACITY);ui(program,"visitsLimit",visits);ui(program,"nearestLimit",nearest);StormCollisionRuntime.bindCurrent(program);
        phase(4,262144);phase(5,CAPACITY);phase(6,CAPACITY);bind(6,scratch);phase(7,CAPACITY);phase(4,262144);phase(5,CAPACITY);phase(8,CAPACITY);
    }
    public void publish(int pool,int counter,ParticleBuffers gpu,long generation,long tick,float time){
        images();bind(1,pool);bind(3,counter);gpu.bindPopulationRead(program,false,generation);StarfallGpuBuffers.INSTANCE.bind(program,false);flag(program,"stateEnabled",StarfallGpuBuffers.INSTANCE.committedBuffer()!=0);ui(program,"capacity",CAPACITY);
        phase(2,CAPACITY);
        // The exact simulation has already committed. Optional export must never roll it back
        // or leave the stable banks in the previous generation.
        try{if(rt!=null){rt.finishConsumers();var slot=rt.acquire(tick,scene,0);if(slot!=null){packet(slot.input,tick,slot.nonce,time,true);rt.submit(slot,tick,scene,0,memberRange(),terrain.count());}}}
        catch(RuntimeException|LinkageError failure){disableNative(failure);}
        finally{read=1-read;pending=false;accepted=null;}
    }
    public void abort(){if(rt!=null)rt.finishConsumers();pending=false;accepted=null;}
    public String description(){return "motion=20Hz probes="+probes+" local="+visits+"/"+nearest+" native="+(rt!=null)+" gl_queries="+glQueries+" terrain_revision="+terrainRevision+"; "+(rt==null?(nativeSelected?(nativeStartPending?"RT waiting for retirement":"RT stopped: "+nativeFailure):"GL LBVH"):rt.description());}
    public boolean nativeActive(){return rt!=null&&!rt.failed();}
    public boolean nativeFailed(){return nativeSelected&&!nativeStartPending&&(rt==null||rt.failed());}
    public boolean nativeSelected(){return nativeSelected;}
    public String backend(){return !nativeSelected?"gl":nativeStartPending?"rt-waiting":nativeFailed()?"rt-failed":"rt";}
    private void disableNative(Throwable failure){
        nativeFailure=failure.toString();
        com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.warn("[CMI particles] RT stopped; select GL explicitly to change backend",failure);
        if(rt!=null)rt.close();rt=null;
    }
    @Override public void close(){abort();if(rt!=null)rt.close();rt=null;if(glIndex!=null)glIndex.close();glIndex=null;terrain.close();for(int t:textures)GpuObjectNames.deleteTexture(t);GpuObjectNames.deleteTexture(metaTexture);GpuObjectNames.deleteTexture(outputTexture);for(int b:states)GpuBufferNames.delete(b);for(int b:new int[]{meta,scratch,input,output,grid})GpuBufferNames.delete(b);GpuPrograms.delete(program);}
}
