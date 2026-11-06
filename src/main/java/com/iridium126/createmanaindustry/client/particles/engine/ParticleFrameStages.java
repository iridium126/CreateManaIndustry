package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.gpu.profile.*;
import java.util.*;

/** Extra ranges for opt-in whole-frame diagnostics. No budget ownership and no default queries. */
public final class ParticleFrameStages {
    private static final Map<String,GpuPassTelemetry> timers=new LinkedHashMap<>();
    private static final Set<String> openScopes=new HashSet<>();
    private ParticleFrameStages(){}
    /** Target only the unresolved sequential section; shadow/other batches are excluded. */
    public static Scope solidSubmission(String label){
        if(!openScopes.contains("world.section.solid_particles"))return null;
        return begin(label,128);
    }
    /** Diagnostic scopes have no publication ownership: preparation may change generations. */
    public static Scope begin(String label){return begin(label,4);}
    private static Scope begin(String label,int slots){
        if(!GpuTimingObserver.active()||openScopes.contains(label))return null;
        var scene=com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE;
        var timer=timers.computeIfAbsent(label,key->new GpuPassTelemetry(slots).named(key));timer.poll();
        timer.begin(true,true,scene.renderFrameToken(),-1);
        openScopes.add(label);
        return new Scope(timer,label);
    }
    public static final class Scope implements AutoCloseable {
        private GpuPassTelemetry timer;
        private final String label;
        private Scope(GpuPassTelemetry timer,String label){this.timer=timer;this.label=label;}
        public void end(boolean success){if(timer!=null){timer.end(success);timer=null;openScopes.remove(label);}}
        @Override public void close(){end(true);}
    }
    public static void draw(String label,Runnable draw){
        if(!GpuTimingObserver.active()){draw.run();return;}
        var scene=com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE;
        var publication=scene.publication();
        draw(label,publication==null?-1:publication.generation(),draw);
    }
    public static void draw(String label,long generation,Runnable draw){
        if(!GpuTimingObserver.active()){draw.run();return;}
        var scene=com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE;
        var timer=timers.computeIfAbsent(label,key->new GpuPassTelemetry().named(key));timer.poll();
        timer.begin(true,true,scene.renderFrameToken(),generation);boolean success=false;
        try{draw.run();success=true;}finally{timer.end(success);}
    }
    public static void poll(){timers.values().forEach(GpuPassTelemetry::poll);}
    public static void close(){timers.values().forEach(GpuPassTelemetry::close);timers.clear();openScopes.clear();}
}
