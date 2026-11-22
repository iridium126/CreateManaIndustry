package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.gpu.state.GpuBindings;

import com.google.gson.GsonBuilder;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.gpu.profile.GpuTimestampRing;
import com.iridium126.createmanaindustry.client.xessfg.XessFgFrameStats;
import com.iridium126.createmanaindustry.client.xessfg.XessFgRuntime;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import com.iridium126.createmanaindustry.infrastructure.output.CMILog;

/** Opt-in whole-client measurements. No waits, glFinish, elapsed queries or particle budget debit. */
public final class ClientFrameCapture {
    public static final ClientFrameCapture INSTANCE=new ClientFrameCapture();
    private static final int CAPACITY=300_000;
    private volatile int requestedSeconds;
    private final StartGate startGate = new StartGate();
    private Window window;
    private long captureGeneration;
    private GpuTimestampRing timer;
    private long start,previousHead,head,frameId;
    private int active=-1;
    private boolean timing;
    private boolean stopAfterFrame;
    private Object level;
    private ParticleDiagnostics.MeasurementContext context;
    private int width,height,limit;
    private boolean vsync;
    static final class StartGate {
        private boolean priming, presented;

        boolean canStart(boolean playable) {
            if (!playable) reset();
            priming = playable && !presented;
            return playable && presented;
        }

        void beforeDisplay(boolean playable) {
            presented = priming && playable;
        }

        void reset() {
            priming = presented = false;
        }
    }

    private static boolean playable(Minecraft mc) {
        return mc.level != null && mc.isWindowActive() && mc.screen == null && mc.getOverlay() == null && !mc.isPaused();
    }
    private static String buildIdentity(){
        try(var input=ClientFrameCapture.class.getResourceAsStream("/cmi-performance-build.sha256")){
            return input==null?"unavailable":new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();
        }catch(java.io.IOException failure){return "unavailable";}
    }
    private static final class ShadowSlot {com.iridium126.createmanaindustry.client.gpu.buffer.GpuReadbackBuffer storage;long fence,frame,generation,display;Window owner;}
    private static final class VisibilitySlot {
        com.iridium126.createmanaindustry.client.gpu.buffer.GpuReadbackBuffer storage;
        long fence,frame;
        boolean light,pose;
        Window owner;
    }
    private record SpatialKey(long worker,int kind,long scene,long nonce) {}
    private record StormKey(long worker,long sequence) {}
    private record StormSubmission(long frame,long identity,long tick,int slot,int members,int terrain,int queued,
                                   int inFlight,int inputBytes,double cpuMs,double inputMs,double releaseMs,double enqueueMs) {}
    private final ShadowSlot[] shadowSlots={new ShadowSlot(),new ShadowSlot(),new ShadowSlot()};
    private final VisibilitySlot[] visibilitySlots=java.util.stream.Stream.generate(VisibilitySlot::new).limit(12).toArray(VisibilitySlot[]::new);
    private final java.nio.ByteBuffer visibilityBytes=org.lwjgl.BufferUtils.createByteBuffer(16);
    private static final class Window {
        final XessFgFrameStats xess = new XessFgFrameStats();
        record StageKey(String name,long generation) {}
        static final class Stage {double cpu,gpu;int cpuSamples,gpuSamples;}
        final Map<Long,Integer> indices=new HashMap<>();
        final Map<Integer,Map<StageKey,Stage>> stages=new TreeMap<>();
        final Map<Integer,Map<String,Object>> riftVisibility=new TreeMap<>();
        final List<String> spatial=new ArrayList<>();
        final List<String> visibility=new ArrayList<>(),poses=new ArrayList<>();
        final Set<SpatialKey> spatialTickets=new HashSet<>();
        final Map<StormKey,StormSubmission> stormTickets=new HashMap<>();
        final List<String> stormSamples=new ArrayList<>();
        final List<String> stormDisplay=new ArrayList<>();
        final List<String> stormInputs=new ArrayList<>();
        long displayRepeated,displayLastSequence=-1,displayExhausted,displayHoldEvents,displaySlotsUnavailable;
        boolean displayHeld;
        int draining,stageEntries;
        final long[] frames=new long[CAPACITY],sceneFrames=new long[CAPACITY],generations=new long[CAPACITY];
        final long[] attacksGeneration=new long[CAPACITY],terrainGeneration=new long[CAPACITY];
        final long[] shadowGenerations=new long[CAPACITY],shadowDisplays=new long[CAPACITY],displayGenerations=new long[CAPACITY],displaySources=new long[CAPACITY];
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
        long stopped,bindingsStart,bindingsAvoidedStart,uniformsStart,uniformsAvoidedStart;
        long copiedStateStart,nativeDisplayGaps,emptyCameraQueries;
        long stagedDisplayBytes,stagedDisplaySamples,stagedDisplayCopyNanos,stagedDisplayUploadNanos;
        int stagedDisplayBacklog;
        String invalid="";
        final Map<String,Object> metadata=new LinkedHashMap<>();
        Window(){Arrays.fill(gpu,Double.NaN);Arrays.fill(cpu,Double.NaN);Arrays.fill(live,-1);Arrays.fill(models,-1);Arrays.fill(body,-1);Arrays.fill(carrier,-1);Arrays.fill(ghost,-1);Arrays.fill(sceneFrames,-1);Arrays.fill(generations,-1);Arrays.fill(shadowGenerations,-1);Arrays.fill(shadowDisplays,-1);Arrays.fill(displayGenerations,-1);Arrays.fill(displaySources,-1);for(var counts:submissions)Arrays.fill(counts,-1);for(var values:camera)Arrays.fill(values,Double.NaN);}
    }
    private ClientFrameCapture(){}
    public synchronized boolean request(int seconds){
        if(seconds<1||seconds>900)throw new IllegalArgumentException("Capture seconds: 1..900");
        if(window!=null||requestedSeconds!=0||ParticleChainCapture.INSTANCE.busy())return false;
        startGate.reset();
        requestedSeconds=seconds;return true;
    }
    public boolean collecting(){return window!=null&&window.collecting;}
    public long collectingGeneration(){return collecting()?captureGeneration:0;}
    public boolean busy(){return window!=null||requestedSeconds!=0;}
    public boolean tracksSpatial(long worker,int kind,long scene,long nonce){return window!=null&&window.spatialTickets.contains(new SpatialKey(worker,kind,scene,nonce));}
    public void stormSubmitted(long worker,long sequence,long identity,long tick,int slot,int members,int terrain,
                               int queued,int inFlight,int inputBytes,double cpuMs,double inputMs,double releaseMs,double enqueueMs){
        var w=window;if(w==null||!w.collecting)return;
        long frame=com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.renderFrameToken();
        w.stormTickets.put(new StormKey(worker,sequence),
                new StormSubmission(frame,identity,tick,slot,members,terrain,queued,inFlight,inputBytes,cpuMs,inputMs,releaseMs,enqueueMs));
    }
    public boolean tracksStorm(long worker,long sequence){
        return window!=null&&window.stormTickets.containsKey(new StormKey(worker,sequence));
    }
    public void stormCompleted(long worker,long sequence,boolean applied,double[] gpuTimes,double consumeMs){
        var w=window;if(w==null)return;
        var s=w.stormTickets.remove(new StormKey(worker,sequence));
        if(s==null)return;
        if(w.stormSamples.size()>=100_000){invalidate("Storm timing record capacity exceeded");return;}
        var row=new StringBuilder().append(s.frame()).append(',').append(worker).append(',').append(s.identity()).append(',')
                .append(s.tick()).append(',').append(sequence).append(',').append(s.slot()).append(',').append(s.members())
                .append(',').append(gpuTimes!=null&&gpuTimes.length>12?(int)gpuTimes[12]:s.terrain()).append(',').append(s.queued()).append(',').append(s.inFlight())
                .append(',').append(s.inputBytes()).append(',').append(applied).append(',').append(s.cpuMs());
        for(int i=0;i<7;i++)row.append(',').append(gpuTimes==null?Double.NaN:gpuTimes[i]);
        row.append(',').append(gpuTimes==null?-1:(int)gpuTimes[7]);
        row.append(',').append(gpuTimes==null?-1:(long)gpuTimes[7]*
                com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.STORM_DISPLAY_RECORD_BYTES);
        row.append(',').append(s.inputMs()).append(',').append(s.releaseMs()).append(',').append(s.enqueueMs()).append(',').append(consumeMs);
        for(int i=8;i<12;i++)row.append(',').append(gpuTimes==null||gpuTimes.length<=i?Double.NaN:gpuTimes[i]);
        w.stormSamples.add(row.toString());
    }
    /** ABI and statistics schema are independent. Timings are fetched only after completion. */
    public void spatial(long sourceTick,long scene,long nonce,int slot,String event,boolean consumed,double[] times,long worker,int kind,int members,int terrain,String view){
        var w=window;if(w==null)return;var key=new SpatialKey(worker,kind,scene,nonce);
        if(event.equals("submitted")){if(!w.collecting)return;w.spatialTickets.add(key);}
        else if(!w.spatialTickets.contains(key))return;
        if(w.spatial.size()>=100_000){invalidate("spatial record capacity exceeded");return;}
        long frame=com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.renderFrameToken();
        var row=new StringBuilder().append(frame).append(',').append(sourceTick).append(',').append(sourceTick+1).append(',').append(scene).append(',').append(nonce).append(',').append(slot).append(',').append(com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.ABI).append(",2,").append(event).append(',').append(consumed);
        for(int i=0;i<5;i++)row.append(',').append(times!=null&&i<times.length?times[i]:Double.NaN);
        row.append(',').append(worker).append(',').append(kind==0?"motion":view).append(',').append(members).append(',').append(terrain);w.spatial.add(row.toString());
        if(event.equals("retired"))w.spatialTickets.remove(key);
    }
    public void invalidate(String reason){if(collecting()){window.invalid=reason;stop();}}
    public boolean cancel() {
        if (requestedSeconds != 0) {
            requestedSeconds = 0;
            startGate.reset();
            return true;
        }
        if (!collecting()) return false;
        invalidate("cancelled by command");
        return true;
    }
    public void close(){requestedSeconds=0;startGate.reset();if(window!=null){window.invalid="client shutdown";finish();}}
    public void begin(){
        if(window==null&&requestedSeconds==0)return;
        frameId++;head=System.nanoTime();active=-1;
        pollShadow();
        pollVisibility(false);
        if(window!=null)XessFgRuntime.pollProfile(window.xess,false);
        if(timer!=null){
            timer.poll();
            ParticleDiagnostics.INSTANCE.pollFrameSamples();
            ParticleFrameStages.poll();
            if (CreateManaIndustry.IRIS_ACTIVE) {
                com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackSession.current().pollFrameTiming();
            }
        }
        if(window!=null&&!window.collecting&&((++window.draining>=8&&timer.pending()==0&&window.spatialTickets.isEmpty()
                &&window.stormTickets.isEmpty()&&!window.xess.hasPending())||head-window.stopped>5_000_000_000L))finish();
        var mc=Minecraft.getInstance();
        if(requestedSeconds!=0&&window==null){
            if(mc.level==null||!mc.isWindowActive()){requestedSeconds=0;startGate.reset();CreateManaIndustry.LOGGER.warn(CMILog.FRAMES + "capture requires an active world window");return;}
            // The first frame after chat closes may still be restoring DLSS and Iris targets.
            if (!startGate.canStart(playable(mc))) return;
            startGate.reset();
            window=new Window();captureGeneration++;window.seconds=requestedSeconds;requestedSeconds=0;
            window.bindingsStart=GpuBindings.submitted();window.bindingsAvoidedStart=GpuBindings.avoided();
            var uniformCounters=com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.currentCache();
            window.uniformsStart=uniformCounters.submissions();window.uniformsAvoidedStart=uniformCounters.avoided();
            level=mc.level;width=mc.getWindow().getWidth();height=mc.getWindow().getHeight();
            limit=mc.options.framerateLimit().get();vsync=mc.options.enableVsync().get();
            context=ParticleDiagnostics.INSTANCE.measurementSnapshot();
            window.copiedStateStart=com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.copiedStateBytes();
            var m=window.metadata;m.put("auto_throttle",com.iridium126.createmanaindustry.infrastructure.config.ClientConfig.particleAutoThrottle);m.put("schema",7);m.put("started",Instant.now().toString());m.put("context",context.description());
            if (context.dlssScale() > 0) m.put("dlss_render_scale", context.dlssScale());
            m.put("camera_scope","current primary level camera and projection; FOV is vertical degrees derived from the actual projection; unavailable values are NaN; compare each orientation independently");
            m.put("build_sha256",buildIdentity());
            m.put("measurement_mode","instrumented-frame-window");
            m.put("shadow_quality",ShadowQualityFrame.current().label());
            m.put("rift_visibility_at_start",com.iridium126.createmanaindustry.client.allaystorm.rift.RiftVisibility.metadata());
            m.put("rift_config",Map.of("clipping",com.iridium126.createmanaindustry.infrastructure.config.ClientConfig.riftSkyClipping,"culling",com.iridium126.createmanaindustry.infrastructure.config.ClientConfig.riftCulling.name()));
            m.put("rift_stage_hierarchy","rift.visibility.prepare is at the main frame boundary, outside rift.visual.whole; rift.visual.whole contains rift.visibility.depth, Julia projection and rift.target.whole; Iris places the visual whole inside iris.translucents.begin; CPU submission counts do not establish GPU conditional execution; depth result remains GPU-only/unknown on CPU");
            m.put("target_sky_visibility_at_start",com.iridium126.createmanaindustry.CreateManaIndustry.IRIS_ACTIVE
                    ?com.iridium126.createmanaindustry.client.render.shaderpack.RiftIrisUniforms.visibilityMetadata()
                    :java.util.Map.of("requested",com.iridium126.createmanaindustry.infrastructure.config.ClientConfig.riftSkyClipping,"effective",com.iridium126.createmanaindustry.infrastructure.config.ClientConfig.riftSkyClipping,"reason","vanilla conservative bounds clipping"));
            m.put("shadow_path_at_start",com.iridium126.createmanaindustry.CreateManaIndustry.IRIS_ACTIVE?com.iridium126.createmanaindustry.client.particles.shaderpack.CMIPackEntityMergeHook.performanceShadowPath():"Iris unavailable");
            m.put("engine_source_sha256",ParticlePrograms.INSTANCE.diagnosticSourceDigest());m.put("native_module_version",com.iridium126.createmanaindustry.client.particles.engine.spatial.SpatialNative.loadedVersion());
            m.put("profile_context_file",ParticleDiagnostics.INSTANCE.exportActiveProfileContext());
            m.put("rt_camera_requested",com.iridium126.createmanaindustry.infrastructure.config.ClientConfig.rtCameraOcclusion);
            m.put("receiving_requested",com.iridium126.createmanaindustry.infrastructure.config.ClientConfig.particleReceiveShadows);
            m.put("rt_visibility_start",com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.particleVisibilityStatus());
            m.put("gold_visible","unavailable: additive command combines materials; no same-generation material census");
            m.put("stage_scope","same scene-frame tagged asynchronous ranges; ranges may nest and must not be summed; absent stages are unavailable/inactive, not zero");
            m.put("stage_hierarchy","world.render contains world.section.*, world.terrain.*, iris.shadow.whole and integrated CMI work; world.section.* are sequential submission sections (may include batch flushes and CMI hooks), terrain and effects are children, never add them to sections; iris.shadow.whole contains draw.pack.model-shadow; submission.pack.merge-whole contains draw.pack.merge and state capture/restore; iris.translucents.begin contains rift.target.whole, iris.translucents.depth-copy and existing deferred; rift.target.whole contains rift.target.prepare/sky/depth-copy/deferred/clouds/composite/history-publish/restore; missing optional children remain unknown; irisveil.before-deferred.callbacks contains registered bridge callbacks (placement depends on bridge); solid_particles.native.* are children of world.section.solid_particles only; batch.type may be nested in batch.all; update.input_state contains starfall.prepare.*; world.effect.whole.<id> contains world.effect.<id>. cpu_ranges/gpu_ranges count repeated executions; when CPU calls are recorded, unequal counts make GPU totals unknown; GPU-only producers have no CPU count; missing ranges remain unknown.");
            com.iridium126.createmanaindustry.client.gpu.profile.GpuTimingObserver.install(this::stageSample);ParticleDiagnostics.INSTANCE.frameSampling(true);
            m.put("process_id",ProcessHandle.current().pid());m.put("process_started",ProcessHandle.current().info().startInstant().map(Object::toString).orElse("unavailable"));m.put("simulation_hz",20);m.put("display_interpolation",true);m.put("query_budget","2/32/4");m.put("backend",com.iridium126.createmanaindustry.client.particles.engine.spatial.SpatialQueryRuntime.INSTANCE.identity());
            m.put("hardware",com.iridium126.createmanaindustry.client.particles.engine.spatial.SpatialQueryRuntime.INSTANCE.status());
            m.put("attack_stage_scope","CPU packet contract stage; per-member GPU phases require input freeze");
            m.put("dimension",mc.level.dimension().location().toString());m.put("size",List.of(width,height));m.put("fps_limit",limit);m.put("vsync",vsync);
            m.put("gpu",org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER));m.put("driver",org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION));
            m.put("gpu_scope","runTick HEAD to before Window.updateDisplay; excludes swap and frame limiter");
            m.put("cpu_scope","runTick HEAD to before Window.updateDisplay; wall submission, not CPU utilization");
            m.put("interval_scope","successive runTick HEAD; includes swap and frame limiter");
            var xess = com.iridium126.createmanaindustry.infrastructure.config.ClientConfig.xessFg();
            m.put("xessfg_config",Map.of("enabled",xess.enabled(),"gpu",xess.gpu().value(),
                    "transport",xess.transport().value(),"surface",xess.surface().value()));
            m.put("xessfg_real_frame_p95_max_increase_percent",5);
            m.put("xessfg_csv_schema",13);
            m.put("xessfg_csv_key","frame joins the main frame CSV; next_tick_interval_ns aligns the interval with the work that produced it");
            m.put("instrumented",true);m.put("fps_claim","requires matched independent-process A/A and A/B; this file alone is not evidence of improvement");
            timer=new GpuTimestampRing(32,null,(tag,generation,ms)->{var w=window;if(w!=null&&tag>=0&&tag<w.size)w.gpu[(int)tag]=ms;});
            // Allocate driver queries and warm the ring before the first measured interval.
            timer.begin(false,-1,-1);timer.end(false,false);start=head;previousHead=0;return;
        }
        if(window==null||!window.collecting)return;
        String reason="";
        if(mc.level!=level)reason="world changed";
        else if(!playable(mc))reason="unfocused or menu open";
        else if(width!=mc.getWindow().getWidth()||height!=mc.getWindow().getHeight())reason="window size changed";
        else if(limit!=mc.options.framerateLimit().get()||vsync!=mc.options.enableVsync().get())reason="limiter changed";
        else {
            var next = ParticleDiagnostics.INSTANCE.measurementSnapshot();
            reason = next.changeFrom(context);
            if (!reason.isEmpty()) {
                window.metadata.put("context_at_invalidation", next.description());
                if (next.dlssScale() > 0) window.metadata.put("dlss_render_scale_at_invalidation", next.dlssScale());
            }
        }
        if(!reason.isEmpty()){window.invalid=reason;stop();return;}
        if(previousHead==0){start=previousHead=head;return;}
        if(window.size==CAPACITY){window.invalid="sample capacity exhausted";stop();return;}
        stopAfterFrame=head-start>=window.seconds*1_000_000_000L;
        active=window.size++;window.frames[active]=frameId;
        long intervalNanos=head-previousHead;
        window.xess.beginFrame(frameId,intervalNanos);
        window.interval[active]=intervalNanos/1e6;previousHead=head;
        var storm=com.iridium126.createmanaindustry.client.allaystorm.AllayStormRuntime.INSTANCE;
        window.simulationTime[active]=storm.timeSec();
        String stage=storm.profileAttackStages();window.attacks[active]=window.stageStrings.computeIfAbsent(stage,key->key);
        window.attacksGeneration[active]=com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.committedGeneration();
        window.terrainGeneration[active]=com.iridium126.createmanaindustry.client.allaystorm.StormCollisionRuntime.profileResourceGeneration();
        timing=timer.begin(false,active,-1);if(!timing)window.skippedGpu++;
    }
    public void beforeDisplay(){
        if (window == null && requestedSeconds != 0) startGate.beforeDisplay(playable(Minecraft.getInstance()));
        if(active<0)return;
        window.riftVisibility.put(active,com.iridium126.createmanaindustry.client.allaystorm.rift.RiftVisibility.metadata());
        if(!window.metadata.get("rift_config").equals(Map.of("clipping",com.iridium126.createmanaindustry.infrastructure.config.ClientConfig.riftSkyClipping,"culling",com.iridium126.createmanaindustry.infrastructure.config.ClientConfig.riftCulling.name())))invalidate("rift configuration changed during capture");
        window.cpu[active]=(System.nanoTime()-head)/1e6;
        if(timing){timer.end(true,false);timing=false;}
    }
    public void end(boolean success){
        if (!success && window == null) startGate.reset();
        if(window!=null)window.xess.endFrame();
        if(timing){timer.end(false,false);timing=false;}
        if(!success&&collecting()){window.invalid="runTick failed";stop();}
        if(stopAfterFrame&&collecting())stop();
        stopAfterFrame=false;
        active=-1;
    }
    private void stop(){
        window.collecting=false;window.stopped=System.nanoTime();
        window.metadata.put("binding_submissions",GpuBindings.submitted()-window.bindingsStart);
        window.metadata.put("bindings_avoided",GpuBindings.avoided()-window.bindingsAvoidedStart);
        window.metadata.put("binding_scope","engine buffer, texture and program calls; foreign renderer calls excluded");
        var uniforms=com.iridium126.createmanaindustry.client.gpu.shader.GpuUniforms.currentCache();
        window.metadata.put("scalar_vector_uniform_submissions",uniforms.submissions()-window.uniformsStart);
        window.metadata.put("scalar_vector_uniforms_avoided",uniforms.avoided()-window.uniformsAvoidedStart);
        window.metadata.put("storm_state_copied_bytes",com.iridium126.createmanaindustry.client.allaystorm.StarfallGpuBuffers.INSTANCE.copiedStateBytes()-window.copiedStateStart);
        window.metadata.put("rt_camera_empty_queries",window.emptyCameraQueries);
        window.metadata.put("native_display_time_domain","native input logical ticks; age excludes server world-time corrections");
        window.metadata.put("native_display_missing_sequences",window.nativeDisplayGaps);
        window.metadata.put("native_display_repeated_sample_frames",window.displayRepeated);
        window.metadata.put("native_display_buffer_exhausted_frames",window.displayExhausted);
        window.metadata.put("native_display_hold_events",window.displayHoldEvents);
        window.metadata.put("native_display_slot_unavailable",window.displaySlotsUnavailable);
        window.metadata.put("storm_staged_display_bytes",window.stagedDisplayBytes);
        window.metadata.put("storm_staged_display_samples",window.stagedDisplaySamples);
        window.metadata.put("storm_staged_display_dtoh_ms",window.stagedDisplayCopyNanos*1e-6);
        window.metadata.put("storm_staged_display_gl_submit_ms",window.stagedDisplayUploadNanos*1e-6);
        window.metadata.put("storm_staged_display_peak_pending",window.stagedDisplayBacklog);
    }
    public void emptyCameraQuery(){if(collecting())window.emptyCameraQueries++;}
    void nativeDisplayGap(long count){if(collecting())window.nativeDisplayGaps+=count;}
    public void nativeDisplaySlotUnavailable(){if(collecting())window.displaySlotsUnavailable++;}
    public void stormDisplayBacklog(int slots){if(collecting())window.stagedDisplayBacklog=Math.max(window.stagedDisplayBacklog,slots);}
    public void stormDisplayTransferred(int bytes,long copyNanos,long glNanos){
        if(!collecting())return;
        window.stagedDisplaySamples++;
        window.stagedDisplayBytes+=bytes;
        window.stagedDisplayCopyNanos+=copyNanos;
        window.stagedDisplayUploadNanos+=glNanos;
    }
    public void stormInputCaptured(long tick,double ms){if(collecting()&&window.stormInputs.size()<100_000)window.stormInputs.add(frameId+","+tick+","+ms);}
    void nativeDisplayReceived(long sequence,long tick,long completedNanos){
        if(!collecting())return;var w=window;
        if(w.stormDisplay.size()<CAPACITY)w.stormDisplay.add(frameId+",received,"+sequence+","+tick+",NaN,false,false,"+(completedNanos==0?Double.NaN:(System.nanoTime()-completedNanos)*1e-6));
    }
    void nativeDisplaySample(long sequence,double age,boolean held,boolean paused){
        if(!collecting())return;var w=window;
        if(!paused){if(sequence==w.displayLastSequence)w.displayRepeated++;if(held){w.displayExhausted++;if(!w.displayHeld)w.displayHoldEvents++;}}
        w.displayLastSequence=sequence;w.displayHeld=held;
        if(w.stormDisplay.size()<CAPACITY)w.stormDisplay.add(frameId+",sample,"+sequence+",-1,"+age+","+held+","+paused+",NaN");
    }
    /** Called with the source frame tag, never associates delayed census with the current frame. */
    public void census(long sceneFrame,long generation,int live,int models,int body,int carrier,int ghost,int feather,int lod1,int lod2,int additive,int sprite,int alpha){
        simulationCensus(sceneFrame,generation,live,models);
        displayCensus(sceneFrame,generation,body,carrier,ghost,feather,lod1,lod2,additive,sprite,alpha);
    }
    public void simulationCensus(long sceneFrame,long generation,int live,int models){
        var w=window;if(w==null)return;Integer i=w.indices.get(sceneFrame);if(i==null||w.generations[i]>generation)return;
        w.generations[i]=generation;w.live[i]=live;w.models[i]=models;
    }
    public void displayCensus(long sceneFrame,long generation,int body,int carrier,int ghost,int feather,int lod1,int lod2,int additive,int sprite,int alpha){
        var w=window;if(w==null)return;Integer i=w.indices.get(sceneFrame);if(i==null||w.displaySources[i]!=generation)return;
        w.body[i]=body;w.carrier[i]=carrier;w.ghost[i]=ghost;
        int[] counts={feather,lod1,lod2,additive,sprite,alpha};for(int j=0;j<counts.length;j++)w.submissions[j][i]=counts[j];
    }
    public void sceneFrame(long frame){if(active>=0){window.sceneFrames[active]=frame;window.indices.put(frame,active);}}
    /** Display and simulation census have separate producers and completion clocks. */
    public void displayPublication(long frame,long publication,long sourceGeneration){
        var w=window;if(w==null)return;Integer i=w.indices.get(frame);if(i==null)return;
        w.displayGenerations[i]=publication;w.displaySources[i]=sourceGeneration;
        if(w.shadowGenerations[i]>=0&&(w.shadowGenerations[i]!=sourceGeneration||w.shadowDisplays[i]!=publication))w.invalid="shadow/display source conflict";
    }
    /** The command source belongs to this exact shadow submission, independent of main-camera visibility. */
    public void shadowSubmitted(long frame,long display,long generation,int commands){
        if(active<0||window.sceneFrames[active]!=frame)return;
        ShadowSlot free=null;for(var slot:shadowSlots)if(slot.fence==0){free=slot;break;}if(free==null)return;
        int read=org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER),write=org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER);
        try{
            if(free.storage==null)free.storage=new com.iridium126.createmanaindustry.client.gpu.buffer.GpuReadbackBuffer(12);
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
            GpuBindings.bind(org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER,commands);GpuBindings.bind(org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER,free.storage.id());
            int[] fields={ParticleBuffers.IDX_CNT_MODELOP,ParticleBuffers.IDX_CNT_CARRIER,ParticleBuffers.IDX_CNT_GHOST};for(int i=0;i<fields.length;i++)com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.copyBufferSubData(org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER,org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER,fields[i]*4L,i*4L,4);
            free.fence=org.lwjgl.opengl.GL32.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);free.frame=frame;free.generation=generation;free.display=display;free.owner=window;
        }finally{GpuBindings.bind(org.lwjgl.opengl.GL31.GL_COPY_READ_BUFFER,read);GpuBindings.bind(org.lwjgl.opengl.GL31.GL_COPY_WRITE_BUFFER,write);}
    }
    private void pollShadow(){
        for(var slot:shadowSlots)if(slot.fence!=0){int status=org.lwjgl.opengl.GL32.glClientWaitSync(slot.fence,0,0);if(status==org.lwjgl.opengl.GL32.GL_TIMEOUT_EXPIRED)continue;
            if(status!=org.lwjgl.opengl.GL32.GL_WAIT_FAILED&&slot.owner==window){Integer i=window.indices.get(slot.frame);if(i!=null){var bytes=java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.nativeOrder());slot.storage.readCompleted(0,12,bytes);window.shadowGenerations[i]=slot.generation;window.shadowDisplays[i]=slot.display;if(window.displaySources[i]>=0&&(window.displaySources[i]!=slot.generation||window.displayGenerations[i]!=slot.display))window.invalid="shadow/display source conflict";for(int k=0;k<3;k++)window.submissions[6+k][i]=Math.max(0,window.submissions[6+k][i])+bytes.getInt(k*4);}}
            org.lwjgl.opengl.GL32.glDeleteSync(slot.fence);slot.fence=0;slot.owner=null;
        }
    }
    private void closeShadow(){for(var slot:shadowSlots){if(slot.fence!=0)org.lwjgl.opengl.GL32.glDeleteSync(slot.fence);slot.fence=0;slot.owner=null;if(slot.storage!=null)slot.storage.close();slot.storage=null;}}
    public void visibilitySubmitted(int buffer,boolean light){
        viewStatsSubmitted(buffer,light,false);
    }
    public void poseSubmitted(int buffer,boolean light){
        viewStatsSubmitted(buffer,light,true);
    }
    private void viewStatsSubmitted(int buffer,boolean light,boolean pose){
        if(buffer==0||!collecting()||active<0)return;
        for(var slot:visibilitySlots)if(slot.fence==0){
            if(slot.storage==null)slot.storage=new com.iridium126.createmanaindustry.client.gpu.buffer.GpuReadbackBuffer(16);
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.memoryBarrier(org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
            com.iridium126.createmanaindustry.client.gpu.buffer.GpuReplayCommands.copyNamedBufferSubData(buffer,slot.storage.id(),pose?12:16,0,pose?8:16);
            slot.fence=org.lwjgl.opengl.GL32.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
            slot.frame=window.sceneFrames[active];slot.light=light;slot.pose=pose;slot.owner=window;
            return;
        }
    }
    private void pollVisibility(boolean closing){
        for(var slot:visibilitySlots){
            if(slot.fence!=0){
                int status=org.lwjgl.opengl.GL32.glClientWaitSync(slot.fence,0,0);
                if(status==org.lwjgl.opengl.GL32.GL_TIMEOUT_EXPIRED&&!closing)continue;
                if(slot.owner==window&&(status==org.lwjgl.opengl.GL32.GL_ALREADY_SIGNALED
                        ||status==org.lwjgl.opengl.GL32.GL_CONDITION_SATISFIED)){
                    visibilityBytes.clear();
                    slot.storage.readCompleted(0,slot.pose?8:16,visibilityBytes);
                    String view=slot.frame+","+(slot.light?"light":"camera")+","+visibilityBytes.getInt(0)+","+visibilityBytes.getInt(4);
                    if(slot.pose){
                        long bytes=(long)visibilityBytes.getInt(4)*(com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.POSE_ROWS_PER_INSTANCE+3)*16;
                        window.poses.add(view+","+bytes);
                    }else window.visibility.add(view+","+visibilityBytes.getInt(8)+","+visibilityBytes.getInt(12));
                }
                org.lwjgl.opengl.GL32.glDeleteSync(slot.fence);slot.fence=0;slot.owner=null;
            }
            if(closing&&slot.storage!=null){slot.storage.close();slot.storage=null;}
        }
    }
    public void camera(long frame,net.minecraft.client.Camera camera,org.joml.Matrix4fc projection){
        if(active<0||window.sceneFrames[active]!=frame)return;
        var position=camera.getPosition();double[] values={position.x,position.y,position.z,camera.getYRot(),camera.getXRot(),projection.m11()>0?Math.toDegrees(2*Math.atan(1.0/projection.m11())):Double.NaN};
        for(int i=0;i<values.length;i++)window.camera[i][active]=values[i];
    }
    private void stageSample(String label,long sceneFrame,long generation,double cpu,double gpu){
        var w=window;if(w==null)return;Integer index=w.indices.get(sceneFrame);if(index==null)return;
        var stages=w.stages.computeIfAbsent(index,key->new LinkedHashMap<>());
        var key=new Window.StageKey(label,generation);var stage=stages.get(key);
        if(stage==null){if(++w.stageEntries>1_000_000){w.invalid="stage sample capacity exceeded";return;}stage=new Window.Stage();stages.put(key,stage);}
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
        if(window!=null)window.metadata.put("rift_visibility_at_end",com.iridium126.createmanaindustry.client.allaystorm.rift.RiftVisibility.metadata());
        if(window!=null)window.metadata.put("shadow_path_at_end",com.iridium126.createmanaindustry.CreateManaIndustry.IRIS_ACTIVE?com.iridium126.createmanaindustry.client.particles.shaderpack.CMIPackEntityMergeHook.performanceShadowPath():"Iris unavailable");
        if(window!=null&&com.iridium126.createmanaindustry.CreateManaIndustry.IRIS_ACTIVE)window.metadata.put("target_sky_visibility_at_end",com.iridium126.createmanaindustry.client.render.shaderpack.RiftIrisUniforms.visibilityMetadata());
        Window w=window;
        XessFgRuntime.pollProfile(w.xess,true);
        w.xess.close();
        w.metadata.put("xessfg_clock_samples",w.xess.clockSamples());
        pollShadow();
        closeShadow();
        pollVisibility(true);
        com.iridium126.createmanaindustry.client.gpu.profile.GpuTimingObserver.install(null);
        ParticleDiagnostics.INSTANCE.frameSampling(false);
        ParticleFrameStages.close();
        if (CreateManaIndustry.IRIS_ACTIVE) {
            com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackSession.current().finishFrameTiming();
        }
        window=null;
        if (timer != null) timer.close();
        timer=null;
        level=null;
        Path directory=Minecraft.getInstance().gameDirectory.toPath().resolve("logs/cmi-particle-frames");
        w.metadata.put("invalid_reason",w.invalid);w.metadata.put("samples",w.size);w.metadata.put("requested_seconds",w.seconds);w.metadata.put("skipped_gpu",w.skippedGpu);
        w.metadata.put("stage_records",w.stageEntries);
        w.metadata.put("spatial_unretired_at_export",w.spatialTickets.size());
        w.metadata.put("storm_unconsumed_at_export",w.stormTickets.size());
        w.metadata.put("storm_gpu_scope","Native Storm tick segments may overlap OpenGL and frame intervals");
        w.metadata.put("rt_native_owned_bytes",com.iridium126.createmanaindustry.client.particles.engine.spatial.SpatialNative.bytes());
        w.metadata.put("rt_visibility_end",com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.particleVisibilityStatus());
        w.metadata.put("spatial_scope","submissions created during this window; lifecycle receipts drain after stop");
        w.metadata.put("interval_ms",statistics(w.interval,w.size));w.metadata.put("cpu_submit_ms",statistics(w.cpu,w.size));w.metadata.put("gpu_ms",statistics(w.gpu,w.size));
        double total=0;for(int i=0;i<w.size;i++)total+=w.interval[i];w.metadata.put("measured_seconds",total/1000);w.metadata.put("average_fps",total>0?w.size*1000/total:0);
        CompletableFuture.runAsync(()->{
            try{
                Files.createDirectories(directory);String name="frames-"+UUID.randomUUID();
                Files.writeString(directory.resolve(name+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(w.metadata)+"\n",StandardOpenOption.CREATE_NEW);
                try(var out=Files.newBufferedWriter(directory.resolve(name+".csv"),StandardOpenOption.CREATE_NEW)){
                    out.write("frame,scene_frame,published_generation,interval_ms,cpu_submit_ms,gpu_ms,live,model_input,body_visible,carrier_visible,ghost_visible,input_time_sec,input_dt_sec,attack_contract_stage,attack_generation,terrain_generation,display_generation,display_source_generation,shadow_source_generation,shadow_display_generation\n");
                    for(int i=0;i<w.size;i++)out.write(w.frames[i]+","+w.sceneFrames[i]+","+w.generations[i]+","+w.interval[i]+","+w.cpu[i]+","+w.gpu[i]+","+w.live[i]+","+w.models[i]+","+w.body[i]+","+w.carrier[i]+","+w.ghost[i]+","+w.simulationTime[i]+","+w.simulationDelta[i]+","+w.attacks[i]+","+w.attacksGeneration[i]+","+w.terrainGeneration[i]+","+w.displayGenerations[i]+","+w.displaySources[i]+","+w.shadowGenerations[i]+","+w.shadowDisplays[i]+"\n");
                }
                try(var out=Files.newBufferedWriter(directory.resolve(name+"-xessfg.csv"),StandardOpenOption.CREATE_NEW)){
                    w.xess.write(out);
                }
                try(var out=Files.newBufferedWriter(directory.resolve(name+"-stages.csv"),StandardOpenOption.CREATE_NEW)){
                    out.write("frame,scene_frame,stage,generation,cpu_submit_ms,gpu_ms,cpu_ranges,gpu_ranges\n");
                    for(var row:w.stages.entrySet())for(var item:row.getValue().entrySet()){int i=row.getKey();var s=item.getValue();var key=item.getKey();out.write(w.frames[i]+","+w.sceneFrames[i]+","+key.name()+","+key.generation()+","+(s.cpuSamples==0?Double.NaN:s.cpu)+","+(s.gpuSamples==0||(s.cpuSamples>0&&s.gpuSamples!=s.cpuSamples)?Double.NaN:s.gpu)+","+s.cpuSamples+","+s.gpuSamples+"\n");}
                }
                try(var out=Files.newBufferedWriter(directory.resolve(name+"-rift-visibility.jsonl"),StandardOpenOption.CREATE_NEW)){
                    var gson=new com.google.gson.Gson();
                    for(var row:w.riftVisibility.entrySet())out.write(gson.toJson(Map.of("frame",w.frames[row.getKey()],"scene_frame",w.sceneFrames[row.getKey()],"visibility",row.getValue()))+"\n");
                }
                try(var out=Files.newBufferedWriter(directory.resolve(name+"-submissions.csv"),StandardOpenOption.CREATE_NEW)){
                    out.write("frame,scene_frame,published_generation,feather,lod1,lod2,additive,sprite,alpha,shadow_body,shadow_carrier,shadow_ghost,shadow_generation\n");
                    for(int i=0;i<w.size;i++){out.write(w.frames[i]+","+w.sceneFrames[i]+","+w.generations[i]);for(var counts:w.submissions)out.write(","+counts[i]);out.write(","+w.shadowGenerations[i]+"\n");}
                }
                Files.write(directory.resolve(name+"-spatial.csv"),java.util.stream.Stream.concat(java.util.stream.Stream.of("scene_frame,source_tick,target_tick,scene_epoch,nonce,slot,native_abi,statistics_schema,event,consumed,external_input_wait_ms,validate_and_instance_prepare_ms,ias_maintenance_ms,probe_and_parameter_upload_ms,seal_and_signal_ms,worker_epoch,query_kind,members,terrain_boxes"),w.spatial.stream()).toList(),StandardOpenOption.CREATE_NEW);
                Files.write(directory.resolve(name+"-rt-visibility.csv"),java.util.stream.Stream.concat(
                        java.util.stream.Stream.of("scene_frame,view,candidates,groups,proof_groups,rejected_instances"),
                        w.visibility.stream()).toList(),StandardOpenOption.CREATE_NEW);
                Files.write(directory.resolve(name+"-poses.csv"),java.util.stream.Stream.concat(
                        java.util.stream.Stream.of("scene_frame,view,candidates,computed,bytes_written"),
                        w.poses.stream()).toList(),StandardOpenOption.CREATE_NEW);
                Files.write(directory.resolve(name+"-storm.csv"),java.util.stream.Stream.concat(java.util.stream.Stream.of(
                        "scene_frame,worker,identity,tick,sequence,display_slot,member_range,terrain_boxes,queued,in_flight,input_bytes,applied,cpu_submit_ms,gpu_control_ms,gpu_instance_prepare_ms,gpu_ias_ms,gpu_trace_ms,gpu_step_ms,gpu_feedback_ms,gpu_display_ms,display_records,display_record_bytes,worker_input_pack_ms,cpu_gl_release_poll_ms,cpu_native_enqueue_ms,cpu_feedback_consume_ms,worker_terrain_index_ms,worker_terrain_geometry_and_staging_ms,gpu_input_upload_ms,terrain_upload_bytes"),
                        w.stormSamples.stream()).toList(),StandardOpenOption.CREATE_NEW);
                Files.write(directory.resolve(name+"-storm-display.csv"),java.util.stream.Stream.concat(
                        java.util.stream.Stream.of("frame,event,sequence,logical_tick,sample_age_ticks,buffer_exhausted,paused,completion_observed_to_ingest_ms"),w.stormDisplay.stream()).toList(),StandardOpenOption.CREATE_NEW);
                Files.write(directory.resolve(name+"-storm-input.csv"),java.util.stream.Stream.concat(
                        java.util.stream.Stream.of("frame,game_tick,owner_thread_capture_ms"),w.stormInputs.stream()).toList(),StandardOpenOption.CREATE_NEW);
                try(var out=Files.newBufferedWriter(directory.resolve(name+"-camera.csv"),StandardOpenOption.CREATE_NEW)){
                    out.write("frame,scene_frame,x,y,z,yaw_degrees,pitch_degrees,vertical_fov_degrees\n");
                    for(int i=0;i<w.size;i++){out.write(w.frames[i]+","+w.sceneFrames[i]);for(var values:w.camera)out.write(","+values[i]);out.write("\n");}
                }
                CreateManaIndustry.LOGGER.info(CMILog.FRAMES + "saved {} ({} samples, invalid={})",directory.resolve(name),w.size,w.invalid);
            }catch(Exception failure){CreateManaIndustry.LOGGER.error(CMILog.FRAMES + "export failed",failure);}
        });
    }
    static Map<String,Object> statistics(double[] input,int count){
        double[] values=Arrays.stream(input,0,count).filter(Double::isFinite).sorted().toArray();
        if(values.length==0)return Map.of("samples",0);
        return Map.of("samples",values.length,"p50",values[(values.length-1)/2],"p95",values[(int)Math.ceil(values.length*.95)-1],"p99",values[(int)Math.ceil(values.length*.99)-1]);
    }
}
