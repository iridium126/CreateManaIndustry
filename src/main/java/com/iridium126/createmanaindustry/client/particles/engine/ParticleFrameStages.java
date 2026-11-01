package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.gpu.profile.*;
import java.util.*;

/** Extra ranges for opt-in whole-frame diagnostics. No budget ownership and no default queries. */
public final class ParticleFrameStages {
    private static final Map<String,GpuPassTelemetry> timers=new LinkedHashMap<>();
    private ParticleFrameStages(){}
    public static void draw(String label,Runnable draw){
        if(!GpuTimingObserver.active()){draw.run();return;}
        var scene=com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE;
        var timer=timers.computeIfAbsent(label,key->new GpuPassTelemetry().named(key));timer.poll();
        var publication=scene.publication();timer.begin(true,true,scene.renderFrameToken(),publication==null?-1:publication.generation());boolean success=false;
        try{draw.run();success=true;}finally{timer.end(success);}
    }
    public static void poll(){timers.values().forEach(GpuPassTelemetry::poll);}
    public static void close(){timers.values().forEach(GpuPassTelemetry::close);timers.clear();}
}
