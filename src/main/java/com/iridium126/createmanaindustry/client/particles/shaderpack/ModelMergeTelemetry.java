package com.iridium126.createmanaindustry.client.particles.shaderpack;

import com.iridium126.createmanaindustry.client.gpu.profile.GpuPassTelemetry;
import java.util.EnumMap;

/** Diagnostic subranges only. The enclosing merge timer owns budget accounting. */
final class ModelMergeTelemetry implements AutoCloseable {
    enum Segment { BODY, CARRIER, GHOST, FEATHER, LOD1, LOD2 }
    private final EnumMap<Segment,GpuPassTelemetry> passes=new EnumMap<>(Segment.class);
    private boolean detailed;
    ModelMergeTelemetry(){for(var segment:Segment.values())passes.put(segment,new GpuPassTelemetry());}
    void configure(boolean details){if(details)passes.values().forEach(GpuPassTelemetry::poll);if(detailed==details)return;detailed=details;passes.values().forEach(timer->timer.configure(details,details));}
    void begin(Segment segment){if(detailed){var timer=passes.get(segment);timer.poll();var scene=com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE;
        timer.begin(true,true,scene.renderFrameToken(),scene.publication().generation());}}
    void end(Segment segment,boolean success){if(detailed)passes.get(segment).end(success);}
    void discardPending(){passes.values().forEach(GpuPassTelemetry::discardPending);}
    String report(boolean details){
        var result=new StringBuilder();
        for(var entry:passes.entrySet())if(entry.getValue().cpuSamples()>0){
            if(!result.isEmpty())result.append('\n');
            result.append("MODEL ").append(entry.getKey().name().toLowerCase(java.util.Locale.ROOT)).append(": ")
                    .append(com.iridium126.createmanaindustry.client.particles.engine.ParticleDiagnostics.INSTANCE.timing(entry.getValue(),details));
        }
        return result.toString();
    }
    @Override public void close(){passes.values().forEach(GpuPassTelemetry::close);detailed=false;}
}
