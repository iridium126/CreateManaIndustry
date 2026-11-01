package com.iridium126.createmanaindustry.client.particles.engine.hardware;

import com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler;
import com.iridium126.createmanaindustry.client.particles.engine.ParticleBuffers;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.lwjgl.opengl.*;

/** Optional device-resident query stage. The caller owns frame publication and
 * the continuation. A late/failed native result always selects the complete GL
 * reference; it never substitutes a previous frame. All methods are render-thread only. */
public final class HardwareQueryStage implements AutoCloseable {
    public record Ticket(int slot,long nonce){}
    private static final AtomicLong EPOCHS=new AtomicLong(1);
    private static final List<HardwareQueryStage> RETIRING=new ArrayList<>();
    private static final class Slot {int source,sourceTexture,result,resultTexture;long nonce,fence;boolean live,consumed;}
    private final Slot[] slots={new Slot(),new Slot(),new Slot()};
    private final int pointCapacity,queryCapacity,bytes,program,requestOffset,ownerOffset,mapOffset,lookupOffset;
    private final long epoch=EPOCHS.getAndIncrement();private long bridge,worker;
    private boolean closing,released,quarantined,retirementFailed;
    private String lastReason="unmeasured";
    private final boolean diagnosticSort;
    private int activePrepare;
    private static int aligned(int value){return (value+255)&~255;}
    public HardwareQueryStage(int points,int queries,int slotBytes,String prepareSource,Path cuda,Path optix,Path source,Path crt){
        this(points,queries,slotBytes,prepareSource,cuda,optix,source,crt,false);
    }
    public HardwareQueryStage(int points,int queries,int slotBytes,String prepareSource,Path cuda,Path optix,Path source,Path crt,boolean diagnosticSort){
        this(points,queries,slotBytes,prepareSource,cuda,optix,source,crt,diagnosticSort,-1);
    }
    public HardwareQueryStage(int points,int queries,int slotBytes,String prepareSource,Path cuda,Path optix,Path source,Path crt,boolean diagnosticSort,int backend){
        this.diagnosticSort=diagnosticSort;
        if(points<1||points>131072||queries<1||queries>262144||slotBytes<4096||slotBytes>64*1024*1024)throw new IllegalArgumentException("Bounded R7 dimensions");
        pointCapacity=points;queryCapacity=queries;bytes=slotBytes;requestOffset=aligned(64+points*16);ownerOffset=aligned(requestOffset+queries*16);mapOffset=aligned(ownerOffset+queries*4);lookupOffset=aligned(mapOffset+points*4);
        if(lookupOffset+(262144+1)*4>bytes)throw new IllegalArgumentException("Input slot cannot hold compact query data");
        program=GpuShaderCompiler.compute("R7 immutable query input",prepareSource);
        activePrepare=program;
        try{
            bridge=HardwareNative.createCopy(bytes,3);int capacity=(bytes-64-aligned(16+(queries+1)*8))/4;
            worker=backend<0?HardwareNative.createAsyncQuery(bridge,points,queries,capacity,epoch,cuda.toString(),optix.toString(),source.toAbsolutePath().toString(),crt.toString()):HardwareNative.createAsyncQueryForBackend(bridge,points,queries,capacity,epoch,backend,cuda.toString(),optix.toString(),source.toAbsolutePath().toString(),crt.toString());
            int previous=GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);try{for(int i=0;i<slots.length;i++){var s=slots[i];int[] buffers=HardwareNative.copyBuffers(bridge,i);s.source=buffers[0];s.result=buffers[1];s.sourceTexture=texture(s.source);s.resultTexture=texture(s.result);}}finally{GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,previous);}
        }catch(RuntimeException|LinkageError failure){close();throw failure;}
    }
    private static int texture(int buffer){int t=GL11.glGenTextures();GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER,t);GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER,GL30.GL_R32UI,buffer);return t;}
    private void ui(String name,int value){GL41.glProgramUniform1ui(activePrepare,GL20.glGetUniformLocation(activePrepare,name),value);}
    private void pair(String name,long value){GL41.glProgramUniform2ui(activePrepare,GL20.glGetUniformLocation(activePrepare,name),(int)value,(int)(value>>>32));}
    private void dispatch(int phase,int groups){ui("phase",phase);GL20.glUseProgram(activePrepare);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute(groups,1,1);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT|GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);}
    public Ticket begin(int pool,int previous,int population,int emitters,int continuationTexture,int strideWords,long inputGeneration,long nonce,int backend){
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,pool);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,5,emitters);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,14,previous);GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,24,population);
        return beginBound(continuationTexture,strideWords,inputGeneration,nonce,backend,pointCapacity,queryCapacity);
    }
    public int prepareProgram(){return program;}
    /** External program remains owned by the caller. Selection changes only GPU
     * preparation; native input and result slots retain their own stage identity. */
    public void selectPrepare(int value){activePrepare=value==0?program:value;}
    public Ticket beginBound(int continuationTexture,int strideWords,long inputGeneration,long nonce,int backend,int pointScan,int queryScan){
        if(closing||quarantined||nonce==0)return null;reap();int index=-1;for(int i=0;i<slots.length;i++)if(!slots[i].live){index=i;break;}if(index<0){lastReason="bounded query ring busy";return null;}
        var s=slots[index];int oldProgram=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        try{
            GL42.glBindImageTexture(6,s.sourceTexture,0,false,0,GL15.GL_READ_WRITE,GL30.GL_R32UI);GL42.glBindImageTexture(7,continuationTexture,0,false,0,GL15.GL_READ_WRITE,GL30.GL_R32UI);
            ui("pointCapacity",pointCapacity);ui("queryCapacity",queryCapacity);ui("strideWords",strideWords);ui("emitterStride",ParticleBuffers.VEC4_PER_EMITTER);ui("lookupOffset",lookupOffset/4);ui("diagnosticSort",diagnosticSort?1:0);pair("inputGeneration",inputGeneration);pair("nonce",nonce);pair("resourceEpoch",epoch);
            GL41.glProgramUniform4ui(activePrepare,GL20.glGetUniformLocation(activePrepare,"offsets"),64,requestOffset,ownerOffset,mapOffset);
            dispatch(0,512);dispatch(1,Math.max(1,(pointScan+255)/256));dispatch(2,Math.max(1,(queryScan+255)/256));dispatch(3,1);
            if(!HardwareNative.submitAsyncQuery(worker,index,nonce,backend,false)){lastReason="native worker rejected query";return null;}s.nonce=nonce;s.live=true;s.consumed=false;return new Ticket(index,nonce);
        }finally{GL20.glUseProgram(oldProgram);}
    }
    /** Bounded CPU consumer wait only observes worker notifications; never calls
     * a blocking CUDA API. A zero budget immediately chooses the full GL fallback. */
    public boolean consume(Ticket ticket,long cpuWaitNanos){
        if(ticket==null)return false;var slot=slot(ticket);long deadline=System.nanoTime()+Math.max(0,cpuWaitNanos);
        for(;;){int state=HardwareNative.pollAsyncQuery(worker,ticket.slot,ticket.nonce);
            if(state==2){HardwareNative.consumeAsyncQuery(worker,ticket.slot,ticket.nonce);GL42.glBindImageTexture(6,slot.resultTexture,0,false,0,GL15.GL_READ_ONLY,GL30.GL_R32UI);slot.consumed=true;lastReason="current native result";return true;}
            if(state==3||state==4){lastReason=HardwareNative.asyncQueryError(worker,ticket.slot,ticket.nonce);quarantined|=state==4;return false;}
            if(System.nanoTime()>=deadline){lastReason="native query not ready at consumer; complete GL reference";return false;}
            LockSupport.parkNanos(50_000);
        }
    }
    private Slot slot(Ticket t){var s=slots[t.slot];if(!s.live||s.nonce!=t.nonce)throw new IllegalStateException("R7 ticket no longer owns this slot");return s;}
    public void afterResume(Ticket t){if(t!=null){var s=slot(t);if(s.consumed){if(s.fence!=0)throw new IllegalStateException("Duplicate R7 consumer fence");s.fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);}}}
    public double[] timing(Ticket ticket){slot(ticket);return HardwareNative.asyncQueryTiming(worker,ticket.slot,ticket.nonce);}
    public long[] statistics(Ticket ticket){if(ticket==null)return new long[]{-1,-1,-1,-1,-1,epoch,-1};slot(ticket);return HardwareNative.asyncQueryStats(worker,ticket.slot,ticket.nonce);}
    /** Offline inspection only; a caller must never put synchronous readback in
     * an FPS window. Ownership must already have returned to GL. */
    public int resultBufferForValidation(Ticket ticket){var s=slot(ticket);if(!s.consumed)throw new IllegalStateException("Native result is not a GL input yet");return s.result;}
    public String lastReason(){return lastReason;}
    public long resourceEpoch(){return epoch;}
    public boolean quarantined(){return quarantined;}
    private void reap(){if(worker==0)return;for(int i=0;i<slots.length;i++){var s=slots[i];if(!s.live)continue;int state=HardwareNative.pollAsyncQuery(worker,i,s.nonce);quarantined|=state==4;if(state==1||state==4||state==5&&s.fence==0)continue;if(HardwareNative.recycleAsyncQuery(worker,i,s.nonce,s.fence)){if(s.fence!=0)GL32.glDeleteSync(s.fence);s.fence=0;s.live=false;s.consumed=false;}}}
    public boolean retired(){return released;}
    private boolean tryRelease(){reap();for(var s:slots)if(s.live&&s.consumed)return false;if(worker!=0&&!HardwareNative.closeAsyncQuery(worker))return false;worker=0;
        for(var s:slots){if(s.sourceTexture!=0)GL11.glDeleteTextures(s.sourceTexture);if(s.resultTexture!=0)GL11.glDeleteTextures(s.resultTexture);s.sourceTexture=s.resultTexture=0;}if(bridge!=0)HardwareNative.closeCopy(bridge);bridge=0;GL20.glDeleteProgram(program);released=true;return true;
    }
    private boolean releaseSafely(){if(retirementFailed)return false;try{return tryRelease();}catch(RuntimeException|LinkageError failure){retirementFailed=quarantined=true;lastReason="unretired native resources isolated after release failure: "+failure;com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.warn("[CMI R7] {}",lastReason);return false;}}
    @Override public void close(){if(released||closing)return;closing=true;if(!releaseSafely())RETIRING.add(this);}
    /** Invoke at render boundaries and shutdown while a GL context is still current. */
    public static void pollRetirement(){RETIRING.removeIf(HardwareQueryStage::releaseSafely);}
    public static int retainedStages(){return RETIRING.size();}
}
