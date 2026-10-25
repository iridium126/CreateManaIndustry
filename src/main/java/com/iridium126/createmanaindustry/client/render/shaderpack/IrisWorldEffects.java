package com.iridium126.createmanaindustry.client.render.shaderpack;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.allaystorm.visual.StormVisualProbe;
import com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene;
import foundry.veil.api.client.render.VeilLevelPerspectiveRenderer;
import net.minecraft.client.Minecraft;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import top.leonx.irisveil.accessors.IrisRenderingPipelineAccessor;
import java.util.*;

/** Native before-composite and generic after-composite routes share one once-per-frame dispatcher. */
public final class IrisWorldEffects {
    private static final class Entry {
        final WorldEffectPass pass;
        long updated=-1,drawn=-1,failed=-1;
        long prepareNanos,session=-1;
        String path="idle",error="",reason="",lastReport="";
        Entry(WorldEffectPass pass){this.pass=pass;}
    }
    private static final Map<String,Entry> passes=new LinkedHashMap<>();
    private static long lastLog;
    private IrisWorldEffects(){}
    public static void register(WorldEffectPass pass){passes.putIfAbsent(pass.id(),new Entry(pass));}
    public static boolean shaderPackActive(){
        return CreateManaIndustry.IRIS_ACTIVE&&Iris.getCurrentPack().isPresent()
                &&Iris.getPipelineManager().getPipelineNullable() instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline;
    }
    public static void render(){render(false);}
    public static void renderBaseline(){if(CreateManaIndustry.VEIL_ACTIVE){StormVisualProbe.init();render(true);}}
    private static void render(boolean baselineStage){
        var mc=Minecraft.getInstance();
        if(mc.level==null||VeilLevelPerspectiveRenderer.isRenderingPerspective())return;
        if(CreateManaIndustry.IRIS_ACTIVE&&ShadowRenderingState.areShadowsCurrentlyBeingRendered())return;
        long frame=ClientGpuScene.INSTANCE.renderFrameToken();
        if(frame<=0)return;
        boolean iris=shaderPackActive();
        Object pipeline=iris?Iris.getPipelineManager().getPipelineNullable():null;
        ShaderPackSession.current().observe(pipeline,mc.getMainRenderTarget().width,mc.getMainRenderTarget().height);
        if(pipeline instanceof IrisRenderingPipelineAccessor bridge)ShaderPackSession.current().verifyNativePrograms(bridge.getProgramSet());
        var capability=ShaderPackSession.current().capabilities();
        for(Entry entry:passes.values()){
            if(entry.session!=ShaderPackSession.current().generation()){entry.session=ShaderPackSession.current().generation();entry.updated=entry.drawn=entry.failed=-1;entry.path="pending";entry.error="";entry.reason="";}
            var pass=entry.pass;
            if(!pass.enabled().getAsBoolean()){entry.path="inactive";entry.reason="effect disabled/no sources";continue;}
            if(!iris&&!pass.probe())continue; // Vanilla mist/glow retain their existing Veil post pipelines.
            var path=iris&&CreateManaIndustry.IRISVEIL_ACTIVE&&!StormVisualProbe.baseline()?capability.worldPath():PackCapabilities.WorldPath.BASELINE;
            String reason=StormVisualProbe.baseline()?"forced baseline":!CreateManaIndustry.IRISVEIL_ACTIVE?"Iris/Veil native bridge unavailable":capability.evidence();
            if(path!=PackCapabilities.WorldPath.BASELINE&&!ShaderPackSession.current().nativeReady()){path=PackCapabilities.WorldPath.BASELINE;reason=ShaderPackSession.current().nativeReason();}
            else if(path!=PackCapabilities.WorldPath.BASELINE&&capability.nativeScene()!=null)reason=ShaderPackSession.current().nativeReason();
            if(baselineStage&&path!=PackCapabilities.WorldPath.BASELINE&&entry.drawn!=frame){
                if(entry.failed!=frame)entry.error="native world window not reached";
                reason="native window unavailable: "+entry.error;
                path=PackCapabilities.WorldPath.BASELINE;
            }
            if(entry.failed==frame){path=PackCapabilities.WorldPath.BASELINE;reason="native attempt failed: "+entry.error;}
            if(baselineStage!=(path==PackCapabilities.WorldPath.BASELINE)||entry.drawn==frame)continue;
            if(entry.updated!=frame){long start=System.nanoTime();pass.update().run();entry.prepareNanos=System.nanoTime()-start;entry.updated=frame;}
            try(var ignored=new WorldEffectState()){
                if(path==PackCapabilities.WorldPath.BASELINE)mc.getMainRenderTarget().bindWrite(false);
                else {
                    if(!(pipeline instanceof IrisRenderingPipelineAccessor bridge)){entry.failed=frame;entry.error="native framebuffer bridge unavailable";continue;}
                    int attachment=path==PackCapabilities.WorldPath.RAW?(pass.mist()?capability.mistBuffer():capability.glowBuffer()):capability.sceneBuffer();
                    bridge.irisveil$bindCompatGbufferFramebuffer(new int[]{attachment});
                }
                if(WorldEffectRenderer.draw(pass,path)){
                    entry.drawn=frame;entry.path=path.name();entry.reason=reason;
                    if(entry.failed!=frame&&!reason.startsWith("native window unavailable"))entry.error="";
                    String report=entry.session+"/"+entry.path+"/"+reason+"/"+entry.error;
                    if(!report.equals(entry.lastReport)){entry.lastReport=report;CreateManaIndustry.LOGGER.info("[CMI effects] {}={} reason={}{}",pass.id(),path,reason,entry.error.isEmpty()?"":"; "+entry.error);}
                }
                else {entry.failed=frame;entry.error=WorldEffectRenderer.failure();}
            }catch(RuntimeException|LinkageError failure){
                entry.failed=frame;entry.error=failure.toString();
                long now=System.currentTimeMillis();if(now-lastLog>5000){lastLog=now;CreateManaIndustry.LOGGER.warn("[CMI effects] {}",pass.id(),failure);}
            }
        }
    }
    public static String report(){
        StringBuilder result=new StringBuilder(ShaderPackSession.current().report());
        result.append(" active=").append(shaderPackActive());
        for(Entry e:passes.values())result.append("\n").append(e.pass.id()).append("=").append(e.path)
                .append(" reason=").append(e.reason)
                .append(" prepare=").append(e.prepareNanos/1000).append("us GPU(copy+draw)=").append(String.format(Locale.ROOT,"%.3f",WorldEffectRenderer.gpuMs(e.pass.id()))).append("ms")
                .append(e.error.isEmpty()?"":"; "+e.error);
        if(CreateManaIndustry.IRIS_ACTIVE){
            result.append("\npackages: ").append(com.iridium126.createmanaindustry.client.packages.render.PackageShaderHook.status());
            if(CreateManaIndustry.HEX_ACTIVE)result.append("\nHex shadow: ").append(com.iridium126.createmanaindustry.client.particles.shaderpack.HexIrisHook.status());
            result.append("\nworld shadow sampling: ").append(ShadowDistortionRegistry.recognized()?"recognized":"disabled (unproven/ambiguous)");
        }
        return result.append("\n").append(StormVisualProbe.status()).toString();
    }
    public static void reload(){WorldEffectRenderer.close();for(Entry e:passes.values()){e.updated=e.drawn=e.failed=-1;e.path="reloading";e.error="";e.reason="resource reload";e.lastReport="";}}
    public static void clear(){reload();}
}
