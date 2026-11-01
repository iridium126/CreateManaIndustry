package com.iridium126.createmanaindustry.client.particles.engine;

import com.google.gson.GsonBuilder;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.gpu.profile.GpuTimestampRing;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;

/** Opt-in whole-client measurements. No waits, glFinish, elapsed queries or particle budget debit. */
public final class ClientFrameCapture {
    public static final ClientFrameCapture INSTANCE=new ClientFrameCapture();
    private static final int CAPACITY=300_000;
    private volatile int requestedSeconds;
    private Window window;
    private GpuTimestampRing timer;
    private long start,previousHead,head,frameId;
    private int active=-1;
    private boolean timing;
    private boolean stopAfterFrame;
    private Object level;
    private String context;
    private int width,height,limit;
    private boolean vsync;
    private static final class ShadowSlot {com.iridium126.createmanaindustry.client.gpu.buffer.GpuReadbackBuffer storage;long fence,frame,generation;Window owner;}
    private final ShadowSlot[] shadowSlots={new ShadowSlot(),new ShadowSlot(),new ShadowSlot()};
    private static final class Window {
        static final class Stage {long generation;double cpu,gpu;int cpuSamples,gpuSamples;Stage(long generation){this.generation=generation;}}
        final Map<Long,Integer> indices=new HashMap<>();
        final Map<Integer,Map<String,Stage>> stages=new TreeMap<>();
        final List<String> hardware=new ArrayList<>();
        int draining,stageEntries;
        final long[] frames=new long[CAPACITY],sceneFrames=new long[CAPACITY],generations=new long[CAPACITY];
        final long[] attacksGeneration=new long[CAPACITY],terrainGeneration=new long[CAPACITY];
        final long[] shadowGenerations=new long[CAPACITY];
        final float[] simulationTime=new float[CAPACITY];
        final float[] simulationDelta=new float[CAPACITY];
        final String[] attacks=new String[CAPACITY];
        final Map<String,String> stageStrings=new HashMap<>();
        final double[] interval=new double[CAPACITY],cpu=new double[CAPACITY],gpu=new double[CAPACITY];
        final double[][] camera=new double[6][CAPACITY];
        final int[] live=new int[CAPACITY],models=new int[CAPACITY],body=new int[CAPACITY],carrier=new int[CAPACITY],ghost=new int[CAPACITY];
        final int[][] submissions=new int[9][CAPACITY];
        int size,seconds,skippedGpu;
        boolean collecting=true;
        long stopped;
        String invalid="";
        final Map<String,Object> metadata=new LinkedHashMap<>();
        Window(){Arrays.fill(gpu,Double.NaN);Arrays.fill(cpu,Double.NaN);Arrays.fill(live,-1);Arrays.fill(models,-1);Arrays.fill(body,-1);Arrays.fill(carrier,-1);Arrays.fill(ghost,-1);Arrays.fill(sceneFrames,-1);Arrays.fill(generations,-1);Arrays.fill(shadowGenerations,-1);for(var counts:submissions)Arrays.fill(counts,-1);for(var values:camera)Arrays.fill(values,Double.NaN);}
    }
    private ClientFrameCapture(){}
    public synchronized boolean request(int seconds){
        if(seconds<1||seconds>300)throw new IllegalArgumentException("Capture seconds: 1..300");
        if(window!=null||requestedSeconds!=0||NeighborInputCapture.INSTANCE.busy()||ParticleQuerySequence.INSTANCE.busy())return false;
        requestedSeconds=seconds;return true;
    }
    public boolean collecting(){return window!=null&&window.collecting;}
    public boolean busy(){return window!=null||requestedSeconds!=0;}
    /** Worker metadata for this exact ticket; -1 is unavailable, never a prior frame's count. */
    public void hardware(long frame,long generation,String phase,long nonce,long[] stats,boolean consumed,String reason){
        var w=window;if(w==null||!w.collecting||!w.indices.containsKey(frame))return;
        if(w.hardware.size()>=100_000){invalidate("hardware record capacity exceeded");return;}
        var row=new StringBuilder().append(frame).append(',').append(generation).append(',').append(phase).append(',').append(nonce);
        for(long value:stats)row.append(',').append(value);row.append(',').append(consumed).append(',').append(reason.replace(',',';').replace('\n',' '));w.hardware.add(row.toString());
    }
    public void invalidate(String reason){if(collecting()){window.invalid=reason;stop();}}
    public void close(){requestedSeconds=0;if(window!=null){window.invalid="client shutdown";finish();}}
    public void begin(){
        com.iridium126.createmanaindustry.client.particles.engine.hardware.HardwareQueryStage.pollRetirement();com.iridium126.createmanaindustry.client.particles.engine.hardware.HardwareCopyStage.pollRetirement();
        if(window==null&&requestedSeconds==0)return;
        frameId++;head=System.nanoTime();active=-1;
        pollShadow();
        if(timer!=null){timer.poll();ParticleDiagnostics.INSTANCE.pollFrameSamples();ParticleFrameStages.poll();}
        if(window!=null&&!window.collecting&&((++window.draining>=8&&timer.pending()==0)||head-window.stopped>5_000_000_000L))finish();
        var mc=Minecraft.getInstance();
        if(requestedSeconds!=0&&window==null){
            if(mc.level==null||!mc.isWindowActive()){requestedSeconds=0;CreateManaIndustry.LOGGER.warn("[CMI frames] capture requires an active world window");return;}
            window=new Window();window.seconds=requestedSeconds;requestedSeconds=0;
            level=mc.level;width=mc.getWindow().getWidth();height=mc.getWindow().getHeight();
            limit=mc.options.framerateLimit().get();vsync=mc.options.enableVsync().get();
            context=ParticleDiagnostics.INSTANCE.measurementContext();
            var m=window.metadata;m.put("auto_throttle",com.iridium126.createmanaindustry.infrastructure.config.ClientConfig.particleAutoThrottle);m.put("schema",4);m.put("started",Instant.now().toString());m.put("context",context);
            m.put("camera_scope","current primary level camera and projection; FOV is vertical degrees derived from the actual projection; unavailable values are NaN; compare each orientation independently");
            m.put("engine_source_sha256",ParticlePrograms.INSTANCE.diagnosticSourceDigest());m.put("diagnostic_receipt_sha256",com.iridium126.createmanaindustry.client.particles.engine.hardware.HardwareBackendRuntime.receiptIdentity());
            m.put("profile_context_file",ParticleDiagnostics.INSTANCE.exportActiveProfileContext());
            m.put("gold_visible","unavailable: additive command combines materials; no same-generation material census");
            m.put("stage_scope","same scene-frame tagged asynchronous ranges; ranges may nest and must not be summed; absent stages are unavailable/inactive, not zero");
            com.iridium126.createmanaindustry.client.gpu.profile.GpuTimingObserver.install(this::stageSample);ParticleDiagnostics.INSTANCE.frameSampling(true);
            m.put("process_id",ProcessHandle.current().pid());m.put("process_started",ProcessHandle.current().info().startInstant().map(Object::toString).orElse("unavailable"));m.put("backend",com.iridium126.createmanaindustry.client.particles.engine.hardware.HardwareBackendRuntime.INSTANCE.identity());
            m.put("hardware",com.iridium126.createmanaindustry.client.particles.engine.hardware.HardwareBackendRuntime.INSTANCE.description());
            m.put("attack_stage_scope","CPU packet contract stage; per-member GPU phases require input freeze");
            m.put("dimension",mc.level.dimension().location().toString());m.put("size",List.of(width,height));m.put("fps_limit",limit);m.put("vsync",vsync);
            m.put("gpu",org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER));m.put("driver",org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION));
            m.put("gpu_scope","runTick HEAD to before Window.updateDisplay; excludes swap and frame limiter");
            m.put("cpu_scope","runTick HEAD to before Window.updateDisplay; wall submission, not CPU utilization");
            m.put("interval_scope","successive runTick HEAD; includes swap and frame limiter");
            m.put("instrumented",true);m.put("fps_claim","requires matched independent-process A/A and A/B; this file alone is not evidence of improvement");
            timer=new GpuTimestampRing(32,null,(tag,generation,ms)->{var w=window;if(w!=null&&tag>=0&&tag<w.size)w.gpu[(int)tag]=ms;});
            // Allocate driver queries and warm the ring before the first measured interval.
            timer.begin(false,-1,-1);timer.end(false,false);start=head;previousHead=0;return;
        }
        if(window==null||!window.collecting)return;
        String reason="";
        if(mc.level!=level)reason="world changed";
        else if(!mc.isWindowActive()||mc.screen!=null)reason="unfocused or menu open";
        else if(width!=mc.getWindow().getWidth()||height!=mc.getWindow().getHeight())reason="window size changed";
        else if(limit!=mc.options.framerateLimit().get()||vsync!=mc.options.enableVsync().get())reason="limiter changed";
        else if(!context.equals(ParticleDiagnostics.INSTANCE.measurementContext()))reason="shader session/options changed";
        if(!reason.isEmpty()){window.invalid=reason;stop();return;}
        if(previousHead==0){start=previousHead=head;return;}
        if(window.size==CAPACITY){window.invalid="sample capacity exhausted";stop();return;}
        stopAfterFrame=head-start>=window.seconds*1_000_000_000L;
        active=window.size++;window.frames[active]=frameId;
        window.interval[active]=(head-previousHead)/1e6;previousHead=head;
        var storm=com.iridium126.createmanaindustry.client.allaystorm.AllayStormRuntime.INSTANCE;
        window.simulationTime[active]=storm.timeSec();
        String stage=storm.profileAttackStages();window.attacks[active]=window.stageStrings.computeIfAbsent(stage,key->key);
        window.attacksGeneration[active]=com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.committedGeneration();
        window.terrainGeneration[active]=com.iridium126.createmanaindustry.client.allaystorm.StormCollisionRuntime.profileResourceGeneration();
        timing=timer.begin(false,active,-1);if(!timing)window.skippedGpu++;
    }
    public void beforeDisplay(){
        if(active<0)return;
        window.cpu[active]=(System.nanoTime()-head)/1e6;
        if(timing){timer.end(true,false);timing=false;}
    }
    public void end(boolean success){
        if(timing){timer.end(false,false);timing=false;}
        if(!success&&collecting()){window.invalid="runTick failed";stop();}
        if(stopAfterFrame&&collecting()){stop();stopAfterFrame=false;}
        active=-1;
    }
    private void stop(){window.collecting=false;window.stopped=System.nanoTime();}
    /** Called with the source frame tag, never associates delayed census with the current frame. */
    public void census(long sceneFrame,long generation,int live,int models,int body,int carrier,int ghost,int feather,int lod1,int lod2,int additive,int sprite,int alpha){
        var w=window;if(w==null)return;
        for(int i=w.size-1;i>=Math.max(0,w.size-256);i--)if(w.sceneFrames[i]==sceneFrame){if(w.shadowGenerations[i]>=0&&w.shadowGenerations[i]!=generation)w.invalid="shadow generation conflict";w.generations[i]=generation;w.live[i]=live;w.models[i]=models;w.body[i]=body;w.carrier[i]=carrier;w.ghost[i]=ghost;int[] counts={feather,lod1,lod2,additive,sprite,alpha};for(int j=0;j<counts.length;j++)w.submissions[j][i]=counts[j];return;}
    }
    public void sceneFrame(long frame){if(active>=0){window.sceneFrames[active]=frame;window.indices.put(frame,active);}}
    /** The command source belongs to this exact shadow submission, independent of main-camera visibility. */
    public void shadowSubmitted(long frame,long generation,int commands){
        if(active<0||window.sceneFrames[active]!=frame)return;
        ShadowSlot free=null;for(var slot:shadowSlots)if(slot.fence==0){free=slot;break;}if(free==null)return;
        int read=org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER),write=org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER);
        try{
            if(free.storage==null)free.storage=new com.iridium126.createmanaindustry.client.gpu.buffer.GpuReadbackBuffer(12);
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER,commands);org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER,free.storage.id());
            int[] fields={ParticleBuffers.IDX_CNT_MODELOP,ParticleBuffers.IDX_CNT_CARRIER,ParticleBuffers.IDX_CNT_GHOST};for(int i=0;i<fields.length;i++)com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.copyBufferSubData(org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER,org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER,fields[i]*4L,i*4L,4);
            free.fence=org.lwjgl.opengl.GL32.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);free.frame=frame;free.generation=generation;free.owner=window;
        }finally{org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER,read);org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER,write);}
    }
    private void pollShadow(){
        for(var slot:shadowSlots)if(slot.fence!=0){int status=org.lwjgl.opengl.GL32.glClientWaitSync(slot.fence,0,0);if(status==org.lwjgl.opengl.GL32.GL_TIMEOUT_EXPIRED)continue;
            if(status!=org.lwjgl.opengl.GL32.GL_WAIT_FAILED&&slot.owner==window){Integer i=window.indices.get(slot.frame);if(i!=null){var bytes=java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.nativeOrder());slot.storage.readCompleted(0,12,bytes);window.shadowGenerations[i]=slot.generation;if(window.generations[i]>=0&&window.generations[i]!=slot.generation)window.invalid="shadow generation conflict";else for(int k=0;k<3;k++)window.submissions[6+k][i]=Math.max(0,window.submissions[6+k][i])+bytes.getInt(k*4);}}
            org.lwjgl.opengl.GL32.glDeleteSync(slot.fence);slot.fence=0;slot.owner=null;
        }
    }
    private void closeShadow(){for(var slot:shadowSlots){if(slot.fence!=0)org.lwjgl.opengl.GL32.glDeleteSync(slot.fence);slot.fence=0;slot.owner=null;if(slot.storage!=null)slot.storage.close();slot.storage=null;}}
    public void camera(long frame,net.minecraft.client.Camera camera,org.joml.Matrix4fc projection){
        if(active<0||window.sceneFrames[active]!=frame)return;
        var position=camera.getPosition();double[] values={position.x,position.y,position.z,camera.getYRot(),camera.getXRot(),projection.m11()>0?Math.toDegrees(2*Math.atan(1.0/projection.m11())):Double.NaN};
        for(int i=0;i<values.length;i++)window.camera[i][active]=values[i];
    }
    private void stageSample(String label,long sceneFrame,long generation,double cpu,double gpu){
        var w=window;if(w==null)return;Integer index=w.indices.get(sceneFrame);if(index==null)return;
        var stages=w.stages.computeIfAbsent(index,key->new TreeMap<>());var stage=stages.get(label);
        if(stage==null){if(++w.stageEntries>1_000_000){w.invalid="stage sample capacity exceeded";return;}stage=new Window.Stage(generation);stages.put(label,stage);}
        if(stage.generation!=generation){w.invalid="stage generation conflict";return;}
        if(Double.isFinite(cpu)){stage.cpu+=cpu;stage.cpuSamples++;}if(Double.isFinite(gpu)){stage.gpu+=gpu;stage.gpuSamples++;}
    }
    public void simulationInput(long sceneFrame,float time,float dt){
        if(active<0||window.sceneFrames[active]!=sceneFrame)return;
        window.simulationTime[active]=time;window.simulationDelta[active]=dt;
        String stage=com.iridium126.createmanaindustry.client.allaystorm.AllayStormRuntime.INSTANCE.profileAttackStages();window.attacks[active]=window.stageStrings.computeIfAbsent(stage,key->key);
        window.attacksGeneration[active]=com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.candidateGeneration();
        window.terrainGeneration[active]=com.iridium126.createmanaindustry.client.allaystorm.StormCollisionRuntime.profileResourceGeneration();
    }
    private void finish(){
        Window w=window;pollShadow();closeShadow();com.iridium126.createmanaindustry.client.gpu.profile.GpuTimingObserver.install(null);ParticleDiagnostics.INSTANCE.frameSampling(false);ParticleFrameStages.close();window=null;timer.close();timer=null;level=null;
        Path directory=Minecraft.getInstance().gameDirectory.toPath().resolve("logs/cmi-particle-frames");
        w.metadata.put("invalid_reason",w.invalid);w.metadata.put("samples",w.size);w.metadata.put("requested_seconds",w.seconds);w.metadata.put("skipped_gpu",w.skippedGpu);
        w.metadata.put("stage_records",w.stageEntries);
        w.metadata.put("interval_ms",statistics(w.interval,w.size));w.metadata.put("cpu_submit_ms",statistics(w.cpu,w.size));w.metadata.put("gpu_ms",statistics(w.gpu,w.size));
        double total=0;for(int i=0;i<w.size;i++)total+=w.interval[i];w.metadata.put("measured_seconds",total/1000);w.metadata.put("average_fps",total>0?w.size*1000/total:0);
        CompletableFuture.runAsync(()->{
            try{
                Files.createDirectories(directory);String name="frames-"+UUID.randomUUID();
                Files.writeString(directory.resolve(name+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(w.metadata)+"\n",StandardOpenOption.CREATE_NEW);
                try(var out=Files.newBufferedWriter(directory.resolve(name+".csv"),StandardOpenOption.CREATE_NEW)){
                    out.write("frame,scene_frame,published_generation,interval_ms,cpu_submit_ms,gpu_ms,live,model_input,body_visible,carrier_visible,ghost_visible,input_time_sec,input_dt_sec,attack_contract_stage,attack_generation,terrain_generation\n");
                    for(int i=0;i<w.size;i++)out.write(w.frames[i]+","+w.sceneFrames[i]+","+w.generations[i]+","+w.interval[i]+","+w.cpu[i]+","+w.gpu[i]+","+w.live[i]+","+w.models[i]+","+w.body[i]+","+w.carrier[i]+","+w.ghost[i]+","+w.simulationTime[i]+","+w.simulationDelta[i]+","+w.attacks[i]+","+w.attacksGeneration[i]+","+w.terrainGeneration[i]+"\n");
                }
                try(var out=Files.newBufferedWriter(directory.resolve(name+"-stages.csv"),StandardOpenOption.CREATE_NEW)){
                    out.write("frame,scene_frame,stage,generation,cpu_submit_ms,gpu_ms,cpu_ranges,gpu_ranges\n");
                    for(var row:w.stages.entrySet())for(var item:row.getValue().entrySet()){int i=row.getKey();var s=item.getValue();out.write(w.frames[i]+","+w.sceneFrames[i]+","+item.getKey()+","+s.generation+","+(s.cpuSamples==0?Double.NaN:s.cpu)+","+(s.gpuSamples==0?Double.NaN:s.gpu)+","+s.cpuSamples+","+s.gpuSamples+"\n");}
                }
                try(var out=Files.newBufferedWriter(directory.resolve(name+"-submissions.csv"),StandardOpenOption.CREATE_NEW)){
                    out.write("frame,scene_frame,published_generation,feather,lod1,lod2,additive,sprite,alpha,shadow_body,shadow_carrier,shadow_ghost,shadow_generation\n");
                    for(int i=0;i<w.size;i++){out.write(w.frames[i]+","+w.sceneFrames[i]+","+w.generations[i]);for(var counts:w.submissions)out.write(","+counts[i]);out.write(","+w.shadowGenerations[i]+"\n");}
                }
                Files.write(directory.resolve(name+"-hardware.csv"),java.util.stream.Stream.concat(java.util.stream.Stream.of("scene_frame,generation,phase,nonce,points,queries,input_live,csr_capacity,position_stage,resource_epoch,overflow,consumed,reason"),w.hardware.stream()).toList(),StandardOpenOption.CREATE_NEW);try(var out=Files.newBufferedWriter(directory.resolve(name+"-camera.csv"),StandardOpenOption.CREATE_NEW)){
                    out.write("frame,scene_frame,x,y,z,yaw_degrees,pitch_degrees,vertical_fov_degrees\n");
                    for(int i=0;i<w.size;i++){out.write(w.frames[i]+","+w.sceneFrames[i]);for(var values:w.camera)out.write(","+values[i]);out.write("\n");}
                }
                CreateManaIndustry.LOGGER.info("[CMI frames] saved {} ({} samples, invalid={})",directory.resolve(name),w.size,w.invalid);
            }catch(Exception failure){CreateManaIndustry.LOGGER.error("[CMI frames] export failed",failure);}
        });
    }
    static Map<String,Object> statistics(double[] input,int count){
        double[] values=Arrays.stream(input,0,count).filter(Double::isFinite).sorted().toArray();
        if(values.length==0)return Map.of("samples",0);
        return Map.of("samples",values.length,"p50",values[(values.length-1)/2],"p95",values[(int)Math.ceil(values.length*.95)-1],"p99",values[(int)Math.ceil(values.length*.99)-1]);
    }
}
