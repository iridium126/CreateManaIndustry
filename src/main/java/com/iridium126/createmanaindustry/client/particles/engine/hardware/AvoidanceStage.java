package com.iridium126.createmanaindustry.client.particles.engine.hardware;

import com.iridium126.createmanaindustry.client.gpu.shader.GpuShaderCompiler;
import com.iridium126.createmanaindustry.client.allaystorm.*;
import com.iridium126.createmanaindustry.client.particles.engine.ParticlePrograms;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.opengl.*;

/** Three independently retired snapshots. No caller waits for a native result. */
public final class AvoidanceStage implements AutoCloseable {
    public static final int CAPACITY=131072,PACKET_BYTES=128+64*CAPACITY;
    private static final AtomicLong EPOCHS=new AtomicLong(1);
    private static final List<AvoidanceStage> RETIRING=new ArrayList<>();
    private static final class Slot {int input,output,texture;long nonce,generation,fence,scene;boolean live,consumed;}
    private final Slot[] slots={new Slot(),new Slot(),new Slot()};
    private final long epoch=EPOCHS.getAndIncrement();
    private int program,update,metadata,grid,fallbackTexture,motionCaps,motionTexture,metadataTexture,domeSolver,domeAudit;
    private int snapshot,alternate;private long bridge,worker,nonce,worldEpoch=-1;private boolean closing,released,quarantined,retirementFailed;
    private long scene=EPOCHS.getAndIncrement();private Object sceneIdentity;
    private Slot accepted;private String reason="no history";
    public AvoidanceStage(String updateSource,boolean nativeProbes,Path cuda,Path optix,Path source,Path crt){
        try{if(nativeProbes&&!RETIRING.isEmpty())throw new IllegalStateException("Prior avoidance resources have not retired");program=GpuShaderCompiler.compute("bounded current avoidance","#version 450 core\n"+ParticlePrograms.commonPrelude()+AvoidanceSource.resource("diagnostics/avoidance.comp"));
        update=GpuShaderCompiler.compute("asynchronous avoidance preference","#version 450 core\n"+ParticlePrograms.commonPrelude()+AvoidanceSource.update(updateSource));
        snapshot=buffer(PACKET_BYTES);alternate=buffer(PACKET_BYTES);metadata=buffer(16L*CAPACITY);grid=buffer(4L*(262144+CAPACITY));
        motionCaps=buffer(32L*CAPACITY);motionTexture=GL45.glCreateTextures(GL31.GL_TEXTURE_BUFFER);GL45.glTextureBuffer(motionTexture,GL30.GL_R32UI,motionCaps);fallbackTexture=texture(snapshot);if(nativeProbes){bridge=HardwareNative.createCopy(PACKET_BYTES,3);worker=HardwareNative.createAvoidance(bridge,CAPACITY,epoch,cuda.toString(),optix.toString(),source.toAbsolutePath().toString(),crt.toString());for(int i=0;i<3;i++){int[] b=HardwareNative.copyBuffers(bridge,i);slots[i].input=b[0];slots[i].output=b[1];slots[i].texture=texture(b[1]);}}}catch(RuntimeException|LinkageError error){close();throw error;}
    }
    private static int texture(int buffer){int id=GL45.glCreateTextures(GL31.GL_TEXTURE_BUFFER);GL45.glTextureBuffer(id,GL30.GL_RGBA32UI,buffer);return id;}
    private static int buffer(long bytes){int id=GL45.glCreateBuffers();GL45.glNamedBufferData(id,bytes,GL15.GL_DYNAMIC_COPY);if(GL45.glGetNamedBufferParameteri64(id,GL15.GL_BUFFER_SIZE)!=bytes){GL15.glDeleteBuffers(id);throw new IllegalStateException("Avoidance buffer allocation failed");}return id;}
    private static void bind(int at,int id){GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,at,id);}
    private static void ui(int p,String name,int value){GL41.glProgramUniform1ui(p,GL20.glGetUniformLocation(p,name),value);}
    private static void pair(int p,String name,long value){GL41.glProgramUniform2ui(p,GL20.glGetUniformLocation(p,name),(int)value,(int)(value>>>32));}
    private void dispatch(int mode,int groups){ui(program,"phase",mode);GL20.glUseProgram(program);GL43.glDispatchCompute(groups,1,1);GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);}
    private void retireAccepted(){if(accepted!=null){accepted.fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);accepted=null;}}
    /** Must run before current update, after all previous hint consumers were submitted. */
    public void begin(long generation,long identityEpoch,Object sceneIdentity){
        GL45.glClearNamedBufferData(motionCaps,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,(java.nio.IntBuffer)null);retireAccepted();if(!Objects.equals(this.sceneIdentity,sceneIdentity)){scene=EPOCHS.getAndIncrement();this.sceneIdentity=sceneIdentity;}if(worldEpoch<0)worldEpoch=identityEpoch;
        if(worldEpoch!=identityEpoch){quarantined=true;reason="world/identity epoch changed; GL local only";return;}
        if(worker==0||quarantined)return;
        for(int i=0;i<3;i++){Slot s=slots[i];if(!s.live||s.consumed)continue;int state=HardwareNative.pollAvoidance(worker,i,s.nonce);if(state==4){quarantined=true;reason="native quarantine";continue;}if(state==2&&s.generation+1==generation&&s.scene==scene){HardwareNative.consumeAvoidance(worker,i,s.nonce);s.consumed=true;accepted=s;reason="completed previous-frame hint; GPU validation required";break;}}
        reap();if(accepted==null)reason="no compatible complete hint; GL local only";
    }
    public int updateProgram(){return update;}
    public void prepareDome(String source,int production){
        int[] parts=com.iridium126.createmanaindustry.client.particles.engine.DomeBodyContacts.related(production);
        if(parts.length!=4)throw new IllegalStateException("Production Dome program set required");
        domeSolver=GpuShaderCompiler.compute("avoidance production Dome contact","#version 450 core\n"+ParticlePrograms.commonPrelude()+source);
        domeAudit=GpuShaderCompiler.compute("avoidance coverage audit","#version 450 core\n"+ParticlePrograms.commonPrelude()+AvoidanceSource.domeAudit(source));
        metadataTexture=texture(metadata);
        com.iridium126.createmanaindustry.client.particles.engine.DomeBodyContacts.register(new com.iridium126.createmanaindustry.client.particles.engine.DomeBodyContacts.Programs(parts[0],domeSolver,parts[2],domeAudit));
    }
    public int domeProgram(){GL42.glBindImageTexture(3,metadataTexture,0,false,0,GL15.GL_READ_ONLY,GL30.GL_RGBA32UI);return domeSolver;}
    public void bindHints(long generation){
        GL42.glBindImageTexture(4,motionTexture,0,false,0,GL15.GL_READ_WRITE,GL30.GL_R32UI);
        GL41.glProgramUniform1i(update,GL20.glGetUniformLocation(update,"uAvoidanceEnabled"),accepted!=null?1:0);pair(update,"uAvoidanceEpoch",epoch);pair(update,"uAvoidanceScene",scene);pair(update,"uAvoidanceGeneration",generation);
        pair(update,"uAvoidanceNonce",accepted==null?0:accepted.nonce);GL42.glBindImageTexture(6,accepted==null?fallbackTexture:accepted.texture,0,false,0,GL15.GL_READ_ONLY,GL30.GL_RGBA32UI);
    }
    private void pack(int destination,int pool,int count,int emitters,int poolCapacity,long generation,float time,float dt,boolean staged){
        bind(0,pool);bind(3,count);bind(5,emitters);bind(6,destination);bind(7,alternate);bind(8,metadata);bind(9,grid);bind(10,snapshot);
        GL42.glBindImageTexture(4,motionTexture,0,false,0,GL15.GL_READ_WRITE,GL30.GL_R32UI);GL41.glProgramUniform1i(program,GL20.glGetUniformLocation(program,"motionCapsEnabled"),1);ui(program,"capacity",CAPACITY);ui(program,"poolCapacity",poolCapacity);pair(program,"nonce",nonce);pair(program,"epoch",epoch);pair(program,"scene",scene);pair(program,"generation",generation);
        GL41.glProgramUniform1f(program,GL20.glGetUniformLocation(program,"timeSec"),time);GL41.glProgramUniform1f(program,GL20.glGetUniformLocation(program,"dt"),dt);
        StarfallGpuBuffers.INSTANCE.bind(program,staged);GL41.glProgramUniform1i(program,GL20.glGetUniformLocation(program,"stateEnabled"),StarfallGpuBuffers.INSTANCE.bytes()>0?1:0);
        dispatch(0,CAPACITY/256);dispatch(1,Math.max(1,(poolCapacity+255)/256));dispatch(2,1);
    }
    /** Both Jacobi steps read a frozen bank and write another bank. */
    public void correct(int pool,int count,int emitters,int poolCapacity,long generation,float time,float dt){
        pack(snapshot,pool,count,emitters,poolCapacity,generation,time,dt,true);StormCollisionRuntime.bindCurrent(program);
        for(int step=0;step<2;step++){bind(6,snapshot);bind(7,alternate);dispatch(3,1024);dispatch(4,CAPACITY/256);dispatch(5,CAPACITY/256);int old=snapshot;snapshot=alternate;alternate=old;}
        bind(6,snapshot);dispatch(3,1024);dispatch(4,CAPACITY/256);dispatch(7,CAPACITY/256);dispatch(8,CAPACITY/256);dispatch(6,CAPACITY/256);
    }
    /** Export after gameplay publication, before world/model rendering. */
    public void publish(int pool,int count,int emitters,int poolCapacity,long generation,float time,float dt){
        retireAccepted();reap();if(worker==0||quarantined||worldEpoch<0||closing)return;
        for(int i=0;i<3;i++)if(slots[i].live&&!slots[i].consumed&&HardwareNative.pollAvoidance(worker,i,slots[i].nonce)==1){reason="previous native work pending; skip duplicate export";return;}
        for(int i=0;i<3;i++){Slot s=slots[i];if(s.live)continue;++nonce;pack(s.input,pool,count,emitters,poolCapacity,generation,time,dt,false);if(HardwareNative.submitAvoidance(worker,i,nonce,generation,scene)){s.live=true;s.consumed=false;s.nonce=nonce;s.generation=generation;s.scene=scene;}else reason="native declined export; GL local only";return;}
        reason="three slots unretired; skip export";
    }
    private void reap(){if(worker==0)return;for(int i=0;i<3;i++){Slot s=slots[i];if(!s.live||s==accepted)continue;int state=HardwareNative.pollAvoidance(worker,i,s.nonce);if(state==4){quarantined=true;continue;}if(state==1||s.consumed&&s.fence==0)continue;if(HardwareNative.recycleAvoidance(worker,i,s.nonce,s.fence)){if(s.fence!=0)GL32.glDeleteSync(s.fence);s.fence=0;s.live=s.consumed=false;}}}
    public String description(){return "ABI10; native="+(worker!=0)+"; result="+reason+"; quarantine="+quarantined+"; epoch="+epoch+"; no native consumer wait";}
    private boolean release(){if(released)return true;retireAccepted();reap();for(Slot s:slots)if(s.live&&s.consumed)return false;if(worker!=0&&!HardwareNative.closeAvoidance(worker))return false;worker=0;if(bridge!=0)HardwareNative.closeCopy(bridge);bridge=0;if(domeSolver!=0)com.iridium126.createmanaindustry.client.particles.engine.DomeBodyContacts.unregister(domeSolver);com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms.delete(domeSolver);com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms.delete(domeAudit);if(metadataTexture!=0)com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(metadataTexture);if(motionTexture!=0)com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(motionTexture);if(fallbackTexture!=0)com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(fallbackTexture);for(Slot s:slots)if(s.texture!=0)com.iridium126.createmanaindustry.client.gpu.state.GpuObjectNames.deleteTexture(s.texture);for(int id:new int[]{snapshot,alternate,metadata,grid,motionCaps})com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(id);com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms.delete(program);com.iridium126.createmanaindustry.client.gpu.shader.GpuPrograms.delete(update);released=true;return true;}
    private boolean releaseSafely(){if(retirementFailed)return false;try{return release();}catch(RuntimeException|LinkageError failure){retirementFailed=quarantined=true;reason="unretired resources isolated: "+failure;return false;}}
    @Override public void close(){if(closing)return;closing=true;if(!releaseSafely())RETIRING.add(this);}
    public static void pollRetirement(){RETIRING.removeIf(AvoidanceStage::releaseSafely);}
}
