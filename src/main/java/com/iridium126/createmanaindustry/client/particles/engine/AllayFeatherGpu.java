package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.allaystorm.*;
import com.iridium126.createmanaindustry.client.allaystorm.lod.StormLodClient;
import com.iridium126.createmanaindustry.client.particles.emitter.*;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import java.nio.ByteBuffer;
import java.util.*;
import static com.iridium126.createmanaindustry.client.particles.emitter.AllayFeatherLayout.*;
import static com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.*;

/** Candidate-local lists/impulses; ordinary particle update owns all motion and lifetimes. */
public final class AllayFeatherGpu implements AutoCloseable {
    public static final AllayFeatherGpu INSTANCE=new AllayFeatherGpu();
    private final int[] state=new int[2];
    private final StormSurfaceGpu surface=new StormSurfaceGpu();
    private final ByteBuffer initial=BufferUtils.createByteBuffer(HEADER*4);
    private final Set<StormCollisionPages.Section> requests=new LinkedHashSet<>();
    private final Set<StormCollisionPages.Section> prewarmKeys=new LinkedHashSet<>();
    private final Set<Integer> armedPrograms=new HashSet<>();
    private int bank,candidate,featherId=-1,goldId=-1,spawn,goldSpawn,frames;
    private long generation,lastCreationGeneration,lastGoldCreationGeneration;
    private double remainder,candidateRemainder,goldRemainder,candidateGoldRemainder;
    private boolean mayFeathers,mayGold,manual,manualGold,candidateBirth,candidateGoldBirth,enabled,nativeDrawn,requestFrame;
    private double radius;
    private Vec3 origin=Vec3.ZERO;
    private Object world;
    private long prewarmTick=Long.MIN_VALUE;
    private int warmRadius=-1;
    private int warmX=Integer.MAX_VALUE,warmY=Integer.MAX_VALUE,warmZ=Integer.MAX_VALUE,warmLow=Integer.MAX_VALUE;
    private AllayFeatherGpu() {}
    public boolean enabled(){return enabled;}
    public boolean needsPlayers(){return enabled&&hasFeathers();}
    public boolean requestFrame(){return hasFeathers()&&requestFrame;}
    private boolean hasFeathers(){return mayFeathers||candidateBirth;}
    public boolean creates(){return spawn>0||goldSpawn>0||manual||manualGold;}
    public int candidateBuffer(){return enabled?ParticleBuffers.INSTANCE.gridBufferId():0;}
    public int committedBuffer(){return mayFeathers?ParticleBuffers.INSTANCE.gridBufferId():0;}
    public long generation(){return generation;}
    public void source(EmitterSpec spec,Vec3 position){
        if(spec.type.id()!=TYPE&&spec.type.id()!=AllayGoldSpecs.TYPE)return;
        var level=Minecraft.getInstance().level;if(world!=level){reset();world=level;}
        if(spec.type.id()==TYPE){manual=true;origin=position;prewarm(position);}else manualGold=true;
    }
    private long offset(int bank){return state[bank]*4L;}
    private int buffer(){return ParticleBuffers.INSTANCE.gridBufferId();}
    private void allocate(){
        var gpu=ParticleBuffers.INSTANCE;int stride=gpu.alignGridWords(WORDS);
        int base=gpu.ensureGridExtra(stride*2);state[0]=base;state[1]=base+stride;
    }
    public void prepare(float dt,float throttle,ParticleRuntime runtime,float partial){
        var mc=Minecraft.getInstance();if(world!=mc.level){reset();world=mc.level;}
        spawn=goldSpawn=0;candidateBirth=manual;candidateGoldBirth=manualGold;candidateRemainder=remainder;candidateGoldRemainder=goldRemainder;
        Vec3 center=null;radius=0;
        if(AllayStormRuntime.INSTANCE.active()){center=AllayStormRuntime.INSTANCE.center();radius=AllayStormRuntime.INSTANCE.radius();}
        else if(StormLodClient.active()&&mc.level!=null){var sample=StormLodClient.CENTERS.sample(mc.level.getGameTime(),partial);if(sample!=null){center=new Vec3(sample.x(),sample.y(),sample.z());radius=sample.radius();}}
        if(center!=null&&mc.level!=null){
            origin=center;double scale=dt*Math.clamp(throttle,0,1);
            double want=remainder+proposals(ClientConfig.allayFeatherMaxSpawnPerSecond,radius)*scale;
            spawn=(int)want;candidateRemainder=want-spawn;candidateBirth|=spawn>0;
            want=goldRemainder+proposals(ClientConfig.allayGoldMaxSpawnPerSecond,radius)*scale;
            goldSpawn=(int)want;candidateGoldRemainder=want-goldSpawn;candidateGoldBirth|=goldSpawn>0;
            if(spawn>0&&(prewarmTick==Long.MIN_VALUE||mc.level.getGameTime()-prewarmTick>=20
                    ||warmX!=((int)Math.floor(origin.x)>>4)||warmY!=((int)Math.floor(origin.y)>>4)||warmZ!=((int)Math.floor(origin.z)>>4)||warmRadius!=(int)Math.ceil(2*radius/16)+1||warmLow!=(int)Math.floor((origin.y-8)/16))){
                prewarmStorm();prewarmTick=mc.level.getGameTime();}
        }else candidateRemainder=candidateGoldRemainder=0;
        surface.frame(mc.level,center,radius,center!=null&&ClientConfig.allayGoldMaxSpawnPerSecond>0);
        enabled=mayFeathers||mayGold||candidateBirth||candidateGoldBirth;requestFrame=(frames&7)==0;
        if(!enabled)return;
        allocate();candidate=1-bank;
        if(hasFeathers()){featherId=runtime.ensureEmitter(AllayFeatherSpecs.FEATHER);ParticleAtlas.ALLAY.ensureLoaded();}
        if(mayGold||candidateGoldBirth)goldId=runtime.ensureEmitter(AllayGoldSpecs.GOLD);
    }
    private void prewarmStorm(){
        int cx=(int)Math.floor(origin.x)>>4,cy=(int)Math.floor(origin.y)>>4,cz=(int)Math.floor(origin.z)>>4,r=(int)Math.ceil(2*radius/16)+1,low=(int)Math.floor((origin.y-8)/16);
        if(cx!=warmX||cy!=warmY||cz!=warmZ||warmRadius!=r||warmLow!=low){
            prewarmKeys.clear();warmX=cx;warmY=cy;warmZ=cz;warmRadius=r;warmLow=low;
            for(int y=low;y<=cy;y++)for(int z=cz-r;z<=cz+r;z++)for(int x=cx-r;x<=cx+r;x++)prewarmKeys.add(new StormCollisionPages.Section(x,y,z));
        }
        StormCollisionRuntime.retainFeathers(prewarmKeys,80);
    }
    private void prewarm(Vec3 p){
        if(p==null||Minecraft.getInstance().level==null)return;
        int cx=(int)Math.floor(p.x)>>4,cy=(int)Math.floor(p.y)>>4,cz=(int)Math.floor(p.z)>>4;
        if(cx!=warmX||cy!=warmY||cz!=warmZ||warmRadius!=-1){
            prewarmKeys.clear();warmX=cx;warmY=cy;warmZ=cz;warmRadius=-1;
            for(int y=cy-1;y<=cy+2;y++)for(int z=cz-2;z<=cz+2;z++)for(int x=cx-2;x<=cx+2;x++)
                prewarmKeys.add(new StormCollisionPages.Section(x,y,z));
        }
        StormCollisionRuntime.retainFeathers(prewarmKeys,80);
    }
    public void begin(){
        if(!enabled)return;
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer());
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.clearBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,offset(candidate),hasFeathers()?BYTES:HEADER*4,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,new int[]{0});
        initial.clear();for(int i=0;i<HEADER;i++)initial.putInt(i*4,0);
        initial.putInt(9*4,1).putInt(10*4,1).putInt(11*4,6);
        for(int i=0;i<3;i++){initial.putInt((16+i)*4,Integer.MAX_VALUE);initial.putInt((19+i)*4,Integer.MIN_VALUE);}
        GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,offset(candidate),initial);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,buffer());GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,buffer());
        if(generation>0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.copyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,offset(bank),offset(candidate)+16,8);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
    }
    public void bind(int program){
        if(!enabled){if(armedPrograms.remove(program)){setIntUniform(program,"uFeatherEnabled",0);setIntUniform(program,"uFeatherRequestFrame",0);}return;}
        armedPrograms.add(program);setIntUniform(program,"uFeatherEnabled",1);
        setIntUniform(program,"uFeatherRequestFrame",requestFrame()?1:0);
        if(enabled){GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,BINDING,buffer(),offset(candidate),BYTES);setUIntUniform(program,"uFeatherGridBase",state[candidate]-ParticleBuffers.GRID_TABLE);}
    }
    public void players(int count){
        if(!hasFeathers()||count==0)return;int p=ParticlePrograms.INSTANCE.featherTools();GL20.glUseProgram(p);bind(p);
        ParticleBuffers.INSTANCE.bindPlayers();setUIntUniform(p,"uPhase",1);setUIntUniform(p,"uPlayerCount",count);
        setUIntUniform(p,"uMovementBase",ParticleBuffers.INSTANCE.featherMovementBase());
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute((count+63)/64,1,1);barrier();
    }
    public void emit(int slot,int capacity,int seed){
        if(!enabled||spawn+goldSpawn==0)return;var gpu=ParticleBuffers.INSTANCE;
        boolean ready=goldSpawn>0&&surface.prepare(ParticlePrograms.INSTANCE.goldSurface(),StormCollisionRuntime.publication(),origin,radius,StormCollisionRuntime.surfaceUploadBudget());
        if(ready)surface.feedback(origin,radius,(frames&7)==0);
        int p=ParticlePrograms.INSTANCE.featherEmit();GL20.glUseProgram(p);gpu.bindParticleWrite(1);gpu.bindCounter(3,slot);bind(p);StormCollisionRuntime.bindCurrent(p);
        int leaves=featherId>=0?spawn:0,gold=goldId>=0&&ready?goldSpawn:0;
        if(leaves+gold==0)return;
        setUIntUniform(p,"uCapacity",capacity);setUIntUniform(p,"uCount",leaves);setUIntUniform(p,"uGoldCount",gold);
        setUIntUniform(p,"uEmitter",Math.max(0,featherId));setUIntUniform(p,"uGoldEmitter",Math.max(0,goldId));
        setFloatUniform(p,"uCenter",(float)origin.x,(float)origin.y,(float)origin.z);setFloatUniform(p,"uRadius",(float)radius);
        setUIntUniform(p,"uSeed",seed);setIntUniform(p,"uSurfaceReady",ready?1:0);if(ready)surface.bindEmitter(p);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute((leaves+gold+63)/64,1,1);barrier();
    }
    public void ground(){
        if(!hasFeathers())return;int p=ParticlePrograms.INSTANCE.featherTools();GL20.glUseProgram(p);bind(p);
        ParticleBuffers.INSTANCE.bindParticleWrite(1);StormCollisionRuntime.bindCurrent(p);
        setUIntUniform(p,"uPhase",0);com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute(1,1,1);barrier();
        setUIntUniform(p,"uPhase",2);
        try{
            GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER,buffer());com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchComputeIndirect(offset(candidate)+8L*4);barrier();
        }finally{
            // The next Storm wave-contact pass consumes the engine's command buffer.
            // Grid head words are particle indices, never valid dispatch dimensions.
            ParticleBuffers.INSTANCE.bindDispatch();
        }
    }
    public void idle(){remainder=candidateRemainder;goldRemainder=candidateGoldRemainder;frames++;}
    public void commit(long nextGeneration){
        if(enabled){bank=candidate;generation=nextGeneration;}
        if(candidateBirth){lastCreationGeneration=nextGeneration;mayFeathers=true;}
        if(candidateGoldBirth){lastGoldCreationGeneration=nextGeneration;mayGold=true;}
        surface.commit();remainder=candidateRemainder;goldRemainder=candidateGoldRemainder;manual=manualGold=false;nativeDrawn=false;frames++;
    }
    public void copySnapshot(ParticleReadbacks.Slot slot){
        surface.copySnapshot(slot);slot.feathers=enabled;slot.featherRequests=requestFrame();if(!enabled)return;
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,buffer());GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,slot.buffer);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.copyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,offset(candidate),32,16);
        if(slot.featherRequests){
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.copyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,offset(candidate)+22L*4,ParticleReadbacks.FEATHER_REQUEST_OFFSET,16);
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.copyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,offset(candidate)+REQUESTS*4L,ParticleReadbacks.FEATHER_REQUEST_OFFSET+16,MAX_REQUESTS*16L);
        }
    }
    public void observe(ParticleReadbacks.Slot slot){
        surface.observe(slot);if(!slot.feathers)return;
        int alive=slot.data.getInt(32),gold=slot.data.getInt(36);
        if(zeroCovers(slot.generation,lastCreationGeneration,alive))mayFeathers=false;
        if(zeroCovers(slot.generation,lastGoldCreationGeneration,gold))mayGold=false;
        if(slot.featherRequests){
            var data=slot.featherData;int n=Math.clamp(data.getInt(0),0,MAX_REQUESTS);requests.clear();
            for(int i=0;i<n;i++){int b=16+i*16;if(data.getInt(b+12)!=1)continue;requests.add(new StormCollisionPages.Section(data.getInt(b),data.getInt(b+4),data.getInt(b+8)));}
            StormCollisionRuntime.retainFeathers(requests,80);
        }
    }
    public void drawMerged(int program){
        if(!mayFeathers||state[bank]==0)return;var gpu=ParticleBuffers.INSTANCE;
        gpu.bindMergedTbos(10,gpu.particleReadBufferId(),buffer());setIntUniform(program,"cmi_FeatherWordBase",state[bank]);gpu.bindVao();
        Camera camera=Minecraft.getInstance().gameRenderer.getMainCamera();camera(program,camera);
        setIntUniform(program,"cmi_FeatherPass",1);setIntUniform(program,"cmi_LodPass",0);
        try{GL11.glDisable(GL11.GL_CULL_FACE);GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,buffer());
            GL40.glDrawArraysIndirect(GL11.GL_TRIANGLES,offset(bank)+11L*4);nativeDrawn=true;
        }finally{setIntUniform(program,"cmi_FeatherPass",0);GL11.glEnable(GL11.GL_CULL_FACE);}
    }
    private static void camera(int program,Camera camera){
        var left=camera.getLeftVector();var up=camera.getUpVector();
        setFloatUniform(program,"uCamRight",-left.x,-left.y,-left.z);setFloatUniform(program,"uCamUp",up.x,up.y,up.z);
        setFloatUniform(program,"cmi_FeatherRight",-left.x,-left.y,-left.z);setFloatUniform(program,"cmi_FeatherUp",up.x,up.y,up.z);
    }
    public void draw(long frameGeneration,Matrix4fc view,Matrix4fc projection,Camera camera){
        if(nativeDrawn||!mayFeathers||generation!=frameGeneration||state[bank]==0)return;
        int p=ParticlePrograms.INSTANCE.featherRender();GL20.glUseProgram(p);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,1,ParticleBuffers.INSTANCE.particleReadBufferId());GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER,20,buffer(),offset(bank),BYTES);
        setMat4Uniform(p,"ModelViewMat",view);setMat4Uniform(p,"ProjMat",projection);var pos=camera.getPosition();setFloatUniform(p,"uCamPos",(float)pos.x,(float)pos.y,(float)pos.z);camera(p,camera);
        ParticleAtlas.ALLAY.bind(1);setIntUniform(p,"uSprite",1);setIntUniform(p,"uMode",1);setFloatUniform(p,"uFadeDist",ClientConfig.particleFadeDistance);
        com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();com.mojang.blaze3d.systems.RenderSystem.depthMask(true);com.mojang.blaze3d.systems.RenderSystem.disableBlend();GL11.glDisable(GL11.GL_CULL_FACE);
        ParticleBuffers.INSTANCE.bindVao();GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER,buffer());GL40.glDrawArraysIndirect(GL11.GL_TRIANGLES,offset(bank)+11L*4);GL11.glEnable(GL11.GL_CULL_FACE);
    }
    public void reset(){StormCollisionRuntime.releaseFeathers();surface.reset();mayFeathers=mayGold=manual=manualGold=enabled=candidateBirth=candidateGoldBirth=nativeDrawn=false;spawn=goldSpawn=0;remainder=candidateRemainder=goldRemainder=candidateGoldRemainder=0;lastCreationGeneration=lastGoldCreationGeneration=generation=0;warmRadius=-1;prewarmTick=Long.MIN_VALUE;frames=0;world=null;warmX=warmY=warmZ=warmLow=Integer.MAX_VALUE;prewarmKeys.clear();requests.clear();}
    @Override public void close(){state[0]=state[1]=0;reset();armedPrograms.clear();featherId=goldId=-1;surface.close();}
    private static void barrier(){com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_COMMAND_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT);}
}
