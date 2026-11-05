package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.gpu.state.GpuBindings;

import java.nio.IntBuffer;
import com.iridium126.createmanaindustry.client.allaystorm.AllayStormRuntime;
import com.iridium126.createmanaindustry.client.allaystorm.lod.StormLodClient;
import com.iridium126.createmanaindustry.client.gpu.buffer.GpuTransfers;
import com.iridium126.createmanaindustry.client.gpu.state.GpuTexture2DState;
import com.iridium126.createmanaindustry.content.allaystorm.*;
import com.iridium126.createmanaindustry.content.allaystorm.lod.StormLodRules;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.FrustumIntersection;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import static com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.*;

/** Render-only analytic particles. Two publication banks, no simulation pool or per-member CPU loop. */
public final class StormLodGpu implements AutoCloseable {
    public static final StormLodGpu INSTANCE=new StormLodGpu();
    public static final int CAPACITY=131072, MASK_WORDS=CAPACITY/32;
    private final int[] records=new int[2],order=new int[2],commands=new int[2];
    private int dead,covered,bank,candidate,uploadedRevision=-1;
    private final IntBuffer mask=BufferUtils.createIntBuffer(MASK_WORDS);
    private final float[] planes=new float[24], candidateBounds=new float[4];
    private final FrustumIntersection coarseFrustum=new FrustumIntersection();
    private boolean candidateActive,active,nativeDrawn;
    private long generation,candidateGeneration;
    private StormIdentity identity;
    private final StormLodTarget target=new StormLodTarget();
    private final com.iridium126.createmanaindustry.client.gpu.profile.GpuPassTelemetry bodyTiming=new com.iridium126.createmanaindustry.client.gpu.profile.GpuPassTelemetry(),pixelTiming=new com.iridium126.createmanaindustry.client.gpu.profile.GpuPassTelemetry();
    private StormLodGpu() {}
    private static int buffer(long bytes){int id=GL15.glGenBuffers();GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,id);GpuTransfers.allocate(GL43.GL_SHADER_STORAGE_BUFFER,bytes,GL15.GL_DYNAMIC_DRAW);return id;}
    private void allocate(){
        ensureCovered();if(dead!=0)return;
        dead=buffer(MASK_WORDS*4L);
        for(int i=0;i<2;i++){records[i]=buffer(CAPACITY*32L);order[i]=buffer(CAPACITY*16L);commands[i]=buffer(40);}
    }
    private void ensureCovered(){if(covered==0)covered=buffer(MASK_WORDS*4L);}
    /** Called before main keygen; its output pool is this generation's real near representation. */
    public void keygen(int program){
        ensureCovered();
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,covered);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.clearBufferData(GL43.GL_SHADER_STORAGE_BUFFER,GL30.GL_R32UI,GL30.GL_RED_INTEGER,GL11.GL_UNSIGNED_INT,new int[]{0});
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,21,covered);
        setIntUniform(program,"uLodCapture",StormLodClient.active()&&StormLodClient.identity.equals(AllayStormRuntime.INSTANCE.identity())?1:0);
        setFloatUniform(program,"uOpaqueCutoff",StormLodRules.near(ClientConfig.particleFadeDistance));
    }
    public void prepare(long nextGeneration,Camera camera,Matrix4fc view,Matrix4fc projection,long displayTick,float partial){
        boolean profiling=ParticleDiagnostics.INSTANCE.enabled();
        bodyTiming.configure(profiling,profiling);pixelTiming.configure(profiling,profiling);target.profiling(profiling);
        bodyTiming.poll();pixelTiming.poll();
        candidateGeneration=nextGeneration;candidate=1-bank;candidateActive=false;
        if(!StormLodClient.active())return;
        allocate();
        var sample=StormLodClient.CENTERS.sample(displayTick,partial);
        if(sample==null)return;
        float near=StormLodRules.near(ClientConfig.particleFadeDistance);
        float far=StormLodRules.limit(StormLodClient.serverLimit,ClientConfig.stormLodDistance);
        if(far<=near)return;
        var cam=camera.getPosition();
        float cx=(float)(sample.x()-cam.x),cy=(float)(sample.y()-cam.y),cz=(float)(sample.z()-cam.z);
        float envelope=(float)(sample.radius()*1.2+16),distance=(float)Math.sqrt(cx*cx+cy*cy+cz*cz);
        Matrix4f vp=extended(projection,far+2).mul(view);
        if(distance+envelope<near||distance-envelope>=far||!coarseFrustum.set(vp).testSphere(cx,cy,cz,envelope))return;
        // A client may first see a storm through the analytic LOD without any MODEL emitter.
        ParticleAtlas.ALLAY.ensureLoaded();if(!ParticleAtlas.ALLAY.ready())return;
        projectedBounds(vp,cx,cy,cz,envelope);
        if(uploadedRevision!=StormLodClient.revision){
            mask.clear();long[] words=StormLodClient.DEAD.toLongArray();
            for(int i=0;i<MASK_WORDS;i++){long word=i/2<words.length?words[i/2]:0;mask.put((int)(word>>>((i&1)*32)));}
            mask.flip();GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,dead);GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,0,mask);uploadedRevision=StormLodClient.revision;
        }
        GpuBindings.bind(GL43.GL_SHADER_STORAGE_BUFFER,commands[candidate]);
        GpuTransfers.upload(GL43.GL_SHADER_STORAGE_BUFFER,0,new int[]{AllayModelGeometry.BODY_OPAQUE_INDEX_COUNT,0,0,0,0,6,0,0,0,0});
        int program=ParticlePrograms.INSTANCE.lodCompute();com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(program);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,0,records[candidate]);GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,1,order[candidate]);
        GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,2,commands[candidate]);GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,3,dead);GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,4,covered);
        var def=StormLodClient.definition;var phase=AllayStormPhases.evaluate(def.creationRadius(),def.finalRadius(),def.growthPerSecond(),def.createdAt(),displayTick,partial,sample.count(),def.seed());
        setUIntUniform(program,"uCount",Math.clamp((int)sample.count(),0,CAPACITY));setFloatUniform(program,"uSeed",def.seed());
        setFloatUniform(program,"uRadius",(float)sample.radius());setFloatUniform(program,"uOmega",phase.omega());setFloatUniform(program,"uRotation",phase.rotation());
        setFloatUniform(program,"uCirculation",phase.conveyor());setFloatUniform(program,"uCirculationRate",phase.conveyorRate());
        setFloatUniform(program,"uCenter",cx,cy,cz);
        setFloatUniform(program,"uNear",near);setFloatUniform(program,"uMiddle",StormLodRules.middle(ClientConfig.stormLod1Distance,near,far));setFloatUniform(program,"uFar",far);
        Vector4f plane=new Vector4f();
        for(int i=0;i<6;i++){vp.frustumPlane(i,plane);planes[i*4]=plane.x;planes[i*4+1]=plane.y;planes[i*4+2]=plane.z;planes[i*4+3]=plane.w;}
        GL20.glUniform4fv(loc(program,"uFrustum"),planes);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.dispatchCompute(Math.max(1,((int)sample.count()+63)/64),1,1);
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_COMMAND_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
        candidateActive=true;
    }
    int displayCommands(long displayGeneration){return ready(displayGeneration)?commands[bank]:0;}
    public void commit(long publishedGeneration){
        if(candidateGeneration!=publishedGeneration)throw new IllegalStateException("LOD generation mismatch");
        bank=candidate;generation=publishedGeneration;active=candidateActive;identity=StormLodClient.identity;nativeDrawn=false;
        if(active)target.bounds(candidateBounds);
    }
    private void projectedBounds(Matrix4fc vp,float x,float y,float z,float radius){
        float minX=1,minY=1,maxX=0,maxY=0;Vector4f corner=new Vector4f();
        for(int i=0;i<8;i++){
            corner.set(x+((i&1)==0?-radius:radius),y+((i&2)==0?-radius:radius),z+((i&4)==0?-radius:radius),1).mul(vp);
            if(corner.w<=0){candidateBounds[0]=candidateBounds[1]=0;candidateBounds[2]=candidateBounds[3]=1;return;}
            float sx=corner.x/corner.w*.5f+.5f,sy=corner.y/corner.w*.5f+.5f;
            minX=Math.min(minX,sx);maxX=Math.max(maxX,sx);minY=Math.min(minY,sy);maxY=Math.max(maxY,sy);
        }
        candidateBounds[0]=Math.clamp(minX,0,1);candidateBounds[1]=Math.clamp(minY,0,1);candidateBounds[2]=Math.clamp(maxX,0,1);candidateBounds[3]=Math.clamp(maxY,0,1);
    }
    public boolean ready(long publishedGeneration){return active&&generation==publishedGeneration&&StormLodClient.active()&&StormLodClient.identity.equals(identity);}
    public static Matrix4f extended(Matrix4fc source,float far){
        Matrix4f result=new Matrix4f(source);float near=source.m32()/(source.m22()-1f);
        // Preserve XY/jitter; only the independent target's perspective depth range changes.
        result.m22(-(far+near)/(far-near));result.m32(-2f*far*near/(far-near));return result;
    }
    public Matrix4f projection(Matrix4fc original){return extended(original,StormLodRules.limit(StormLodClient.serverLimit,ClientConfig.stormLodDistance)+2);}
    public void drawNative(long publishedGeneration,Matrix4fc view,Matrix4fc projection,java.util.function.Consumer<Matrix4f> draw){
        if(!ready(publishedGeneration))return;
        target.frameContext(ParticleDiagnostics.INSTANCE.lastFrame(),generation);target.draw(projection,projection(projection),()->draw.accept(projection(projection)));
        nativeDrawn=true;
    }
    /** Native variant reads the same buffers through the engine's existing texture-buffer views. */
    public void bindNative(){ParticleBuffers.INSTANCE.bindMergedTbos(10,records[bank],order[bank]);ParticleBuffers.INSTANCE.bindVao();GpuBindings.bind(GL40.GL_DRAW_INDIRECT_BUFFER,commands[bank]);}
    public void drawBody(){
        bodyTiming.begin(ParticleDiagnostics.INSTANCE.enabled(),ParticleDiagnostics.INSTANCE.enabled(),ParticleDiagnostics.INSTANCE.lastFrame(),generation);
        try{GL40.glDrawElementsIndirect(GL11.GL_TRIANGLES,GL11.GL_UNSIGNED_INT,0L);}finally{bodyTiming.end();}
    }
    public void drawPixel(){
        pixelTiming.begin(ParticleDiagnostics.INSTANCE.enabled(),ParticleDiagnostics.INSTANCE.enabled(),ParticleDiagnostics.INSTANCE.lastFrame(),generation);
        try{GL40.glDrawArraysIndirect(GL11.GL_TRIANGLES,20L);}finally{pixelTiming.end();}
    }
    public void fallback(long publishedGeneration,Matrix4fc view,Matrix4fc projection){
        if(nativeDrawn||!ready(publishedGeneration))return;
        target.frameContext(ParticleDiagnostics.INSTANCE.lastFrame(),generation);target.draw(projection,projection(projection),()->{
            int program=ParticlePrograms.INSTANCE.lodRender();com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(program);
            setMat4Uniform(program,"ModelViewMat",view);setMat4Uniform(program,"ProjMat",projection(projection));setMat4Uniform(program,"cmi_LodInverseView",new Matrix4f(view).invert());
            ParticleBuffers.INSTANCE.bindVao();ParticleBuffers.INSTANCE.bindModelGeo();
            GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,0,records[bank]);GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,1,order[bank]);
            // Model geometry remains bound at BIND_MODELGEO by the engine.
            ParticleAtlas.ALLAY.bind(1);setIntUniform(program,"uSprite",1);
            GpuBindings.bind(GL40.GL_DRAW_INDIRECT_BUFFER,commands[bank]);
            RenderSystem.enableDepthTest();RenderSystem.depthMask(true);RenderSystem.disableBlend();RenderSystem.disableCull();
            setIntUniform(program,"cmi_LodPass",1);drawBody();
            int pixel=ParticlePrograms.INSTANCE.lodPixel();com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(pixel);
            setMat4Uniform(pixel,"ModelViewMat",view);setMat4Uniform(pixel,"ProjMat",projection(projection));setMat4Uniform(pixel,"cmi_LodInverseView",new Matrix4f(view).invert());
            setIntUniform(pixel,"uSprite",1);setIntUniform(pixel,"uMode",1);setIntUniform(pixel,"cmi_LodPass",2);drawPixel();
        });
    }
    public String report(){return "Storm LOD: "+(active?"generation="+generation+", "+(nativeDrawn?"native allay MRT":"standalone opaque"):"inactive")+", buffers="+String.format(java.util.Locale.ROOT,"%.2f MiB",bytes()/1048576.0)+", "+target.report()+"\nLOD1: "+ParticleDiagnostics.INSTANCE.timing(bodyTiming,true)+"; LOD2: "+ParticleDiagnostics.INSTANCE.timing(pixelTiming,true);}
    public long bytes(){return (covered==0?0:MASK_WORDS*4L)+(dead==0?0:CAPACITY*96L+MASK_WORDS*4L+80);}
    @Override public void close(){
        for(int[] list:new int[][]{records,order,commands})for(int i=0;i<2;i++){if(list[i]!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(list[i]);list[i]=0;}
        if(dead!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(dead);if(covered!=0)com.iridium126.createmanaindustry.client.gpu.buffer.GpuBufferNames.delete(covered);
        dead=covered=0;uploadedRevision=-1;active=false;bodyTiming.close();pixelTiming.close();target.close();
    }
}
