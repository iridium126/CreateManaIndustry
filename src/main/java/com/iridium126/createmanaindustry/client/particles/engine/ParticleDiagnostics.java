package com.iridium126.createmanaindustry.client.particles.engine;

import java.lang.management.ManagementFactory;
import java.util.*;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.client.gpu.profile.GpuBudgetLedger;
import com.iridium126.createmanaindustry.client.gpu.profile.ProfileReportFormat;

/** Opt-in CPU submit/allocations and asynchronous timestamp samples. No GL_TIME_ELAPSED ownership. */
public final class ParticleDiagnostics {
    public static final ParticleDiagnostics INSTANCE = new ParticleDiagnostics();
    private static final int SAMPLES = 240, MARKERS = 32;
    private static final class Frame {
        final int[] queries = new int[MARKERS];
        final String[] names = new String[MARKERS];
        int count;
        boolean pending;
    }
    private final Frame[] frames = {new Frame(),new Frame(),new Frame(),new Frame()};
    private final double[] cpu = new double[SAMPLES], allocations = new double[SAMPLES];
    private final Map<String, Double> gpu = new LinkedHashMap<>();
    private final com.sun.management.ThreadMXBean memory = ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean m ? m : null;
    public enum UpdateStage { INPUT_PREPARE, INPUT_STATE, INPUT_ALLOCATOR, INPUT_RESET, INPUT_DAMAGE, INPUT_GRID, PREPARE, RECOVERY, GENERIC, MODEL, DOME_CONTACTS, DEPENETRATION, NAVIGATION, FEEDBACK, PUBLICATION }
    private final EnumMap<UpdateStage,com.iridium126.createmanaindustry.client.gpu.profile.GpuPassTelemetry> updates=new EnumMap<>(UpdateStage.class);
    private UpdateStage activeUpdate;
    private UpdateStage activePreparation;
    private boolean frameSampling;
    public ParticleDiagnostics(){for(var stage:UpdateStage.values())updates.put(stage,new com.iridium126.createmanaindustry.client.gpu.profile.GpuPassTelemetry().named("update."+stage.name().toLowerCase(Locale.ROOT)));}
    public void frameSampling(boolean value){frameSampling=value;updates.values().forEach(timer->timer.configure(enabled||value,enabled||value));}
    public void pollFrameSamples(){updates.values().forEach(com.iridium126.createmanaindustry.client.gpu.profile.GpuPassTelemetry::poll);}
    public long firstFrame(){return firstFrame;}
    public long lastFrame(){return lastFrame;}
    public String timing(com.iridium126.createmanaindustry.client.gpu.profile.GpuPassTelemetry timer,boolean details){return timer.report(firstFrame,lastFrame,details);}
    public void beginUpdate(UpdateStage stage,long frame,long generation){
        if(!enabled&&!frameSampling)return;if(activeUpdate!=null)throw new IllegalStateException("Nested update diagnostic");
        activeUpdate=stage;var timer=updates.get(stage);timer.poll();timer.begin(true,true,frame,generation);
    }
    public void beginPreparation(UpdateStage stage,long frame,long generation){
        if(!enabled&&!frameSampling)return;
        if(activeUpdate!=UpdateStage.INPUT_PREPARE||activePreparation!=null)throw new IllegalStateException("Invalid input preparation scope");
        activePreparation=stage;var timer=updates.get(stage);timer.poll();timer.begin(true,true,frame,generation);
    }
    public void endPreparation(boolean success){if(activePreparation!=null){updates.get(activePreparation).end(success);activePreparation=null;}}
    public void endUpdate(boolean success){endPreparation(false);if(activeUpdate!=null){updates.get(activeUpdate).end(success);activeUpdate=null;}}
    private String updateReport(){
        var result=new StringBuilder();for(var entry:updates.entrySet())result.append("\nUpdate ").append(entry.getKey().name().toLowerCase(Locale.ROOT)).append(": ").append(timing(entry.getValue(),true));return result.toString();
    }
    private int simulationLive=-1,simulationModel=-1;
    private long simulationFrame=-1,simulationGeneration=-1;
    private int captureLive=-1,captureModels=-1;
    private long captureCensusFrame=-1;
    public void simulationCounts(long frame,long generation,int live,int models){
        captureCensusFrame=frame;captureLive=live;captureModels=models;
        if(enabled&&frame>=firstFrame){simulationLive=live;simulationModel=models;simulationFrame=frame;simulationGeneration=generation-1;}
    }
    private int cursor, samples;
    private long firstFrame=-1,lastFrame=-1,countFrame=-1,countGeneration=-1;
    private int bodyCount,carrierCount,ghostCount,featherCount,lod1Count,lod2Count;
    private long optionsRevision=Long.MIN_VALUE;
    private com.iridium126.createmanaindustry.infrastructure.config.ParticleShadowQuality optionsShadowQuality;
    private String optionSummary="unavailable";
    private final long[] sampleFrames=new long[SAMPLES];
    private String captureContext="unmeasured";
    private Object captureWorld;
    public void modelCounts(long frame,long generation,int body,int carrier,int ghost,int feather,int lod1,int lod2){
        modelCounts(frame,generation,body,carrier,ghost,feather,lod1,lod2,-1,-1,-1);
    }
    public void modelCounts(long frame,long generation,int body,int carrier,int ghost,int feather,int lod1,int lod2,int additive,int sprite,int alpha){
        if(captureCensusFrame==frame)com.iridium126.createmanaindustry.client.particles.engine.ClientFrameCapture.INSTANCE.census(frame,generation,captureLive,captureModels,body,carrier,ghost,feather,lod1,lod2,additive,sprite,alpha);
        if(!enabled||frame<firstFrame)return;
        countFrame=frame;countGeneration=generation;bodyCount=body;carrierCount=carrier;ghostCount=ghost;featherCount=feather;lod1Count=lod1;lod2Count=lod2;
    }
    private void captureContext(){
        var scene=com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE;
        lastFrame=scene.renderFrameToken();if(firstFrame<0)firstFrame=lastFrame;
        var window=net.minecraft.client.Minecraft.getInstance().getWindow();
        String nextContext=window.getWidth()+"x"+window.getHeight()+"; "+
                measurementContext();
        var level=net.minecraft.client.Minecraft.getInstance().level;
        nextContext+="; world="+(level==null?"none":level.dimension().location());
        if(captureContext(nextContext,lastFrame,level)&&com.iridium126.createmanaindustry.CreateManaIndustry.IRISVEIL_ACTIVE)
            com.iridium126.createmanaindustry.client.particles.shaderpack.CMIPackEntityMergeHook.restartMeasurementWindow();
    }
    boolean captureContext(String nextContext,long frame,Object world){
        lastFrame=frame;
        boolean changed=!captureContext.equals("unmeasured")&&(!captureContext.equals(nextContext)||captureWorld!=world);
        if(changed){releaseQueries();samples=cursor=0;gpu.clear();firstFrame=lastFrame;countFrame=countGeneration=-1;simulationLive=simulationModel=-1;simulationFrame=simulationGeneration=-1;updates.values().forEach(timer->{timer.close();timer.configure(enabled,enabled);});}
        captureContext=nextContext;captureWorld=world;return changed;
    }
    /** Kept behind the optional dependency guard; standalone profiling never resolves Iris types. */
    private String irisCaptureContext(){
        var session=com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackSession.current();
        var active=net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable();
        if(!(active instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline))
            return "pack=(off); source=none; options=unavailable";
        if(!session.ownsPipeline(active))return "pack=pending; source=none; options=unavailable";
        if(optionsRevision!=session.revision()||optionsShadowQuality!=ShadowQualityFrame.current()){
            optionsShadowQuality=ShadowQualityFrame.current();
            optionsRevision=session.revision();var options=session.options();
            if(options==null)optionSummary="unavailable";
            else{
                var values=new TreeMap<String,String>();
                options.getOptionSet().getBooleanOptions().keySet().forEach(key->values.put(key,Boolean.toString(options.getOptionValues().getBooleanValueOrDefault(key))));
                options.getOptionSet().getStringOptions().keySet().forEach(key->values.put(key,options.getOptionValues().getStringValueOrDefault(key)));
                try{var digest=java.security.MessageDigest.getInstance("SHA-256");
                    optionSummary=values.size()+" options / "+java.util.HexFormat.of().formatHex(digest.digest(values.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,12);
                }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
            }
            if(com.iridium126.createmanaindustry.CreateManaIndustry.IRISVEIL_ACTIVE)
                com.iridium126.createmanaindustry.client.particles.shaderpack.ParticleProfileSnapshot.write(session,active);
        }
        return "pack="+net.irisshaders.iris.Iris.getCurrentPackName()+"; source="+session.inventory().digest().substring(0,12)+"; options="+optionSummary+"; session="+session.revision();
    }
    /** Explicit opt-in export, tied to the current owning pack session. */
    public String exportActiveProfileContext(){
        if(!com.iridium126.createmanaindustry.CreateManaIndustry.IRIS_ACTIVE||!com.iridium126.createmanaindustry.CreateManaIndustry.IRISVEIL_ACTIVE)return "none";
        var path=com.iridium126.createmanaindustry.client.particles.shaderpack.ParticleProfileSnapshot.write(com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackSession.current(),net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable());return path==null?"unavailable":path.toString();
    }
    /** Active pipeline identity for opt-in whole-frame captures; optional Iris remains guarded. */
    public String measurementContext(){return (com.iridium126.createmanaindustry.CreateManaIndustry.IRIS_ACTIVE?irisCaptureContext():"pack=none; source=none; options=unavailable")+"; backend="+com.iridium126.createmanaindustry.client.particles.engine.hardware.HardwareBackendRuntime.INSTANCE.identity()+"; engine="+ParticlePrograms.INSTANCE.diagnosticSourceDigest()+"; shadow_quality="+ShadowQualityFrame.current().label()+"; auto_throttle="+com.iridium126.createmanaindustry.infrastructure.config.ClientConfig.particleAutoThrottle;}
    private Frame current;
    private long start, allocatedStart, cpuNanos, allocatedBytes, uploads, calls, draws, readbackLag;
    private long lastUploads, lastCalls, lastDraws, lastReadbackLag;
    private volatile boolean requested,enabled;
    public void enabled(boolean enabled) { requested=enabled; }
    public boolean enabled() { return enabled||frameSampling; }
    public boolean hasSamples() { return samples>0; }
    /** Render-thread boundary: command changes cannot split an active measurement. */
    public void applyRequestedState() {
        boolean next=requested;
        if(next==enabled)return;
        releaseQueries();
        if(next){samples=cursor=0;gpu.clear();firstFrame=lastFrame=countFrame=countGeneration=-1;captureContext="unmeasured";optionsRevision=Long.MIN_VALUE;simulationLive=simulationModel=-1;simulationFrame=simulationGeneration=-1;}
        enabled=next;updates.values().forEach(timer->timer.configure(next||frameSampling,next||frameSampling));if(!next)captureWorld=null;
        com.iridium126.createmanaindustry.client.gpu.profile.GpuQueueAccounting.collecting(next);
        com.iridium126.createmanaindustry.client.packages.feedback.PackageCpuDiagnostics.enabled(next);
        com.iridium126.createmanaindustry.client.packages.world.PackageWorldRuntime.profiling(next);
        if(com.iridium126.createmanaindustry.CreateManaIndustry.HEX_ACTIVE)
            com.iridium126.createmanaindustry.client.hex.pattern.HexMetrics.collecting(next);
    }
    public void begin() {
        if (!enabled) return;
        captureContext();updates.values().forEach(com.iridium126.createmanaindustry.client.gpu.profile.GpuPassTelemetry::poll);
        cpuNanos = allocatedBytes = uploads = calls = draws = readbackLag = 0;
        for (Frame f : frames) if (f.pending && GL15.glGetQueryObjecti(f.queries[f.count-1], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
            long previous = GL33.glGetQueryObjectui64(f.queries[0], GL15.GL_QUERY_RESULT);
            for (int i=1; i<f.count; i++) {
                long value = GL33.glGetQueryObjectui64(f.queries[i], GL15.GL_QUERY_RESULT);
                if (!f.names[i-1].equals("world")) gpu.put(f.names[i-1], (value-previous)/1e6);
                previous = value;
            }
            f.pending = false;
        }
        current = null;
        for (Frame f : frames) if (!f.pending) { current = f; break; }
        if (current != null) {
            if (current.queries[0] == 0) GL15.glGenQueries(current.queries);
            current.count = 0;
        }
        resumeCpu(); mark("upload");
    }
    public void resumeCpu() {
        if (!enabled) return;
        start = System.nanoTime(); allocatedStart = allocated();
    }
    @SuppressWarnings("deprecation") // Preserve getId() overrides on custom Thread subclasses.
    private long allocated() {
        return memory != null && memory.isThreadAllocatedMemorySupported() && memory.isThreadAllocatedMemoryEnabled()
                ? memory.getThreadAllocatedBytes(Thread.currentThread().getId()) : 0;
    }
    public void pauseCpu() {
        if (!enabled) return;
        cpuNanos += System.nanoTime()-start; allocatedBytes += Math.max(0,allocated()-allocatedStart);
    }
    public void mark(String name) {
        if (!enabled || current == null || current.count == MARKERS) return;
        int index=current.count++;
        current.names[index]=name;
        GL33.glQueryCounter(current.queries[index], GL33.GL_TIMESTAMP);
    }
    public void upload(long bytes) { if(enabled) uploads+=bytes; }
    public void call() { if(enabled) calls++; }
    /** Counts API submissions; one multi-draw call counts once, irrespective of its sub-draws. */
    public void drawCall() { if(enabled) {calls++;draws++;} }
    public void readback(long lag) { if(enabled) readbackLag=Math.max(readbackLag,lag); }
    public void end() {
        if (!enabled) return;
        mark("end"); pauseCpu();
        if (current != null && current.count > 0) current.pending=true;
        current=null;
        cpu[cursor]=cpuNanos/1e6; allocations[cursor]=allocatedBytes;
        sampleFrames[cursor]=lastFrame;cursor=(cursor+1)%SAMPLES;samples=Math.min(SAMPLES,samples+1);firstFrame=sampleFrames[(cursor-samples+SAMPLES)%SAMPLES];
        lastUploads=uploads;lastCalls=calls;lastDraws=draws;lastReadbackLag=readbackLag;
        com.iridium126.createmanaindustry.client.packages.feedback.PackageCpuDiagnostics.frame();
    }
    public String summary(GpuBudgetLedger.Snapshot frame) {
        return formatReport(frame,false);
    }
    public String report(GpuBudgetLedger.Snapshot frame) {
        return formatReport(frame,true);
    }
    private String formatReport(GpuBudgetLedger.Snapshot frame,boolean details) {
        String header=enabled?"[CMI profile] collecting; waiting for first frame":"[CMI profile] off; /cmi particle profile on";
        if(samples==0)return header;
        header="[CMI profile] "+(enabled?"collecting":"stopped; last capture")+"; frames="+samples+"/"+SAMPLES
                +(details?"; details":"; /cmi particle profile details");
        double[] sorted=Arrays.copyOf(cpu,samples);Arrays.sort(sorted);
        double meanAllocation=Arrays.stream(allocations,0,samples).average().orElse(0);
        var report=new StringBuilder(header).append(String.format(Locale.ROOT,
                "\nCPU submit p50/p95=%.3f/%.3f ms; prepare latest=%.3f ms; alloc=%s/frame",
                sorted[samples/2],sorted[Math.min(samples-1,(int)(samples*.95))],frame.cpuNanos()/1e6,ProfileReportFormat.bytes(meanAllocation)));
        report.append(String.format(Locale.ROOT,"\nGPU completed latest=%.3f ms; stages latest (ms%s): ",frame.gpuMillis(),details?"":", top 5"))
                .append(details?ProfileReportFormat.timings(gpu):ProfileReportFormat.slowest(gpu,5));
        report.append("\nIO latest: upload=").append(ProfileReportFormat.bytes(frame.uploadedBytes()))
                .append("; readback=").append(ProfileReportFormat.bytes(frame.readbackBytes()))
                .append("; tracked GL/draw=").append(lastCalls).append('/').append(lastDraws)
                .append("; readback lag=").append(lastReadbackLag).append(" gen");
        if(details&&lastUploads!=frame.uploadedBytes())report.append("; particle tracked upload=").append(ProfileReportFormat.bytes(lastUploads));
        if((frame.accepted()|frame.deferred()|frame.rejected()|frame.backlog())!=0)
            report.append("\nQueue latest accepted/deferred/rejected/backlog=").append(frame.accepted()).append('/')
                    .append(frame.deferred()).append('/').append(frame.rejected()).append('/').append(frame.backlog());
        if(details){
            report.append("\nCapture frames=").append(firstFrame).append("..").append(lastFrame).append("; ").append(captureContext);
            report.append(updateReport());
            report.append("\nUpdate linked resources: ").append(ParticlePrograms.INSTANCE.updateInterface());
            report.append("\nSimulation input counts: ");
            if(simulationLive<0)report.append("unmeasured");
            else report.append("live=").append(simulationLive).append("; MODEL=").append(simulationModel<0?"unavailable":Integer.toString(simulationModel))
                    .append("; non-model=").append(simulationModel<0?"unavailable":Integer.toString(Math.max(0,simulationLive-simulationModel)))
                    .append("; frame=").append(simulationFrame).append("; input generation=").append(simulationGeneration);
            report.append("\nMODEL submitted counts: ");
            if(countFrame<0)report.append("unmeasured");
            else report.append("body/carrier/ghost/feather/LOD1/LOD2=").append(bodyCount).append('/').append(carrierCount).append('/').append(ghostCount).append('/').append(featherCount).append('/').append(lod1Count).append('/').append(lod2Count)
                    .append("; frame=").append(countFrame).append("; generation=").append(countGeneration);
        }
        return report.toString();
    }
    public void close() {
        releaseQueries();updates.values().forEach(com.iridium126.createmanaindustry.client.gpu.profile.GpuPassTelemetry::close);activeUpdate=activePreparation=null;samples=cursor=0;gpu.clear();captureWorld=null;captureContext="unmeasured";firstFrame=lastFrame=countFrame=countGeneration=-1;simulationLive=simulationModel=-1;simulationFrame=simulationGeneration=-1;optionsRevision=Long.MIN_VALUE;
    }
    private void releaseQueries() {
        for(Frame f:frames) {
            for(int q:f.queries)if(q!=0)GL15.glDeleteQueries(q);
            Arrays.fill(f.queries,0);f.pending=false;f.count=0;
        }
        current=null;
    }
}
