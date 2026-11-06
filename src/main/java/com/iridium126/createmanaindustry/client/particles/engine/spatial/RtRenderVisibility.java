package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.gpu.buffer.*;
import com.iridium126.createmanaindustry.client.gpu.shader.*;
import com.iridium126.createmanaindustry.client.gpu.state.GpuBindings;
import com.iridium126.createmanaindustry.client.particles.engine.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.lwjgl.opengl.*;

/** Independent render queries. Camera and shadow proofs have separate rows and coverage. */
public final class RtRenderVisibility implements AutoCloseable {
    private static final int GROUPS=1024,ROWS=GROUPS;
    private static final AtomicLong EPOCHS=new AtomicLong(1);
    private final long epoch=EPOCHS.getAndIncrement();
    private final Function<String,String> sources;
    private final RenderTerrain coverage;
    private final boolean lightView;
    private RtQueryWorker worker;
    private int program,bounds,masks,historyInput,historyOutput;
    private long frame,historyScene=Long.MIN_VALUE,historyTerrain=Long.MIN_VALUE;
    private long submittedGeneration=Long.MIN_VALUE,submittedScene=Long.MIN_VALUE,submittedTerrain=Long.MIN_VALUE;
    private Vec3 submittedCamera;
    private final Vector3f submittedLight=new Vector3f();
    private boolean submittedShadow;
    private boolean failed;
    private String failure="";
    private final Vector3f light=new Vector3f();
    public RtRenderVisibility(Function<String,String> sources,RenderTerrain coverage,boolean lightView){
        this.sources=sources;this.coverage=coverage;this.lightView=lightView;
    }
    private static int buffer(long bytes){int id=GL45.glCreateBuffers();GL45.glNamedBufferData(id,bytes,GL15.GL_DYNAMIC_COPY);
        if(GL45.glGetNamedBufferParameteri64(id,GL15.GL_BUFFER_SIZE)!=bytes){GpuBufferNames.delete(id);throw new IllegalStateException("RT visibility allocation failed");}return id;}
    private void allocate() throws java.io.IOException {
        if(worker==null){
            if(RtQueryWorker.resourcesRetiring())return;
            worker=new RtQueryWorker(ROWS,SpatialTerrain.CAPACITY,2,epoch,ParticleRtModules.prepare(),lightView?"light":"camera");
        }
        if(program!=0)return;
        program=GpuShaderCompiler.compute("RT render candidates","#version 450 core\n"+ParticlePrograms.commonPrelude()+sources.apply("spatial/visibility.comp"));
        GpuProgramContract.verify(program,"spatial/visibility.comp");
        bounds=buffer(GROUPS*32L);masks=buffer(32+GROUPS*48L);
        long bytes=128L+ROWS*64L;historyInput=buffer(bytes);historyOutput=buffer(bytes);
    }
    private void pair(String name,long value){com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform2ui(program,GpuUniforms.loc(program,name),(int)value,(int)(value>>>32));}
    private void ui(String name,int value){com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1ui(program,GpuUniforms.loc(program,name),value);}
    private void flag(String name,boolean value){com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1i(program,GpuUniforms.loc(program,name),value?1:0);}
    private static void bind(int at,int value){GpuBindings.bindBase(GL43.GL_SHADER_STORAGE_BUFFER,at,value);}
    private static void barrier(){GpuReplayCommands.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT|GL43.GL_COMMAND_BARRIER_BIT);}
    private void phase(int value,int groups){ui("phase",value);GpuReplayCommands.dispatchCompute(groups,1,1);barrier();}
    public int prepare(ParticleBuffers gpu,int pool,int counter,int emitters,int dispatch,long generation,float time,
                       Vec3 camera,boolean enabled,Vector3f ray,float[] planes){
        RtQueryWorker.retire();
        if(!enabled){stopWorker();return 0;}
        if(failed)return 0;
        try{
            if(!coverage.prepare(camera)){stopWorker();return 0;}
            var terrain=coverage.terrain();long scene=coverage.scene();long tick=++frame;
            if(worker==null&&terrain.count()==0)return 0;
            allocate();if(worker==null||program==0)return 0;
            GpuBindings.program(program);
            bind(4,historyInput);bind(5,historyOutput);
            var accepted=worker.take(tick,scene,1);
            if(accepted!=null){
                if(accepted.terrainRevision==terrain.revision()){
                    bind(8,accepted.input);bind(9,accepted.output);
                    flag("mergeHistory",historyScene==scene&&historyTerrain==terrain.revision());
                    phase(4,GROUPS/64);
                    historyScene=accepted.scene;historyTerrain=accepted.terrainRevision;
                }
                worker.finishConsumers();
            }
            if(worker.failed()){failed=true;failure=worker.description();stopWorker();return 0;}
            if(terrain.count()==0)return 0;
            com.iridium126.createmanaindustry.client.gpu.state.GpuBindings.program(program);bind(0,pool);bind(1,bounds);bind(2,counter);bind(4,historyInput);bind(5,historyOutput);bind(6,masks);bind(7,emitters);
            gpu.bindPopulationRead(program,false,generation);
            com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform3f(program,GpuUniforms.loc(program,"camera"),(float)camera.x,(float)camera.y,(float)camera.z);
            if(lightView)light.set(ray);else light.zero();
            if(!lightView)GL41.glProgramUniform4fv(program,GpuUniforms.loc(program,"cameraPlanes"),planes);
            flag("shadowEnabled",lightView);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform3f(program,GpuUniforms.loc(program,"shadowRay"),light.x,light.y,light.z);
            flag("historyEnabled",historyScene==scene&&historyTerrain==terrain.revision());
            flag("measure",ClientFrameCapture.INSTANCE.collecting());
            ParticleFrameStages.draw(lightView?"rt.light.groups":"rt.camera.groups",()->{
                phase(0,GROUPS/64);ui("phase",1);GpuBindings.bind(GL43.GL_DISPATCH_INDIRECT_BUFFER,dispatch);GpuReplayCommands.dispatchComputeIndirect(12);barrier();phase(3,GROUPS/64);
            });
            boolean needsQuery=submittedGeneration!=generation||submittedScene!=scene||submittedTerrain!=terrain.revision()||submittedCamera==null
                    ||Math.abs(camera.x-submittedCamera.x)>.125||Math.abs(camera.y-submittedCamera.y)>.125||Math.abs(camera.z-submittedCamera.z)>.125
                    ||lightView!=submittedShadow||Math.abs(light.x-submittedLight.x)>.0005f||Math.abs(light.y-submittedLight.y)>.0005f||Math.abs(light.z-submittedLight.z)>.0005f;
            if(!worker.failed()&&needsQuery){
                var slot=worker.acquire(tick,scene,1);
                if(slot!=null){
                    worker.terrain(scene,terrain);bind(3,slot.input);pair("nonce",slot.nonce);pair("epoch",epoch);pair("sourceTick",tick);pair("scene",scene);
                    ui("terrainCount",terrain.count());ui("probes",2);com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.uniform1f(program,GpuUniforms.loc(program,"timeSec"),time);
                    ParticleFrameStages.draw(lightView?"rt.light.packet":"rt.camera.packet",()->phase(2,ROWS/64));
                    barrier();if(worker.submit(slot,tick,scene,1,ROWS,terrain.count())){
                        submittedGeneration=generation;submittedScene=scene;submittedTerrain=terrain.revision();submittedCamera=camera;submittedLight.set(light);submittedShadow=lightView;
                    }
                }
            }
            if(worker.failed()){failed=true;failure=worker.description();stopWorker();return 0;}
            return masks;
        }catch(RuntimeException|LinkageError|java.io.IOException error){
            failed=true;failure=error.toString();CreateManaIndustry.LOGGER.warn("[CMI particles] RT render queries stopped: {}",failure);
            stopWorker();return 0;
        }
    }
    public String status(){return "render="+(failed?"rt-failed":worker==null?"uninitialized":"rt")+" "+failure+(worker==null?"":" "+worker.description());}
    public void stopWorker(){
        if(worker!=null)worker.close();worker=null;
        historyScene=historyTerrain=submittedGeneration=submittedScene=submittedTerrain=Long.MIN_VALUE;submittedCamera=null;
    }
    @Override public void close(){stopWorker();for(int id:new int[]{bounds,masks,historyInput,historyOutput})GpuBufferNames.delete(id);GpuPrograms.delete(program);program=bounds=masks=historyInput=historyOutput=0;failed=false;failure="";}
}
