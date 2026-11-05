package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import com.iridium126.createmanaindustry.client.allaystorm.AllayStormRuntime;
import com.iridium126.createmanaindustry.client.particles.engine.ClientFrameCapture;
import java.util.Set;

/** Selects the Storm simulation owner at an identity boundary. */
public final class SpatialQueryRuntime implements AutoCloseable {
    public static final SpatialQueryRuntime INSTANCE=new SpatialQueryRuntime();
    private String requested=System.getProperty("cmi.particle.backend","rt");
    private boolean nativeSelected=!requested.equals("gl"),switchPending,resetGl;
    private SpatialScene scene;
    public enum GlWork { STORM_STEP, COLUMN_RECOVERY, DOME_CONTACT, DEPENETRATION, NAVIGATION, ATTACK_BATCH }
    private final long[] glWork=new long[GlWork.values().length];
    private SpatialQueryRuntime(){}
    public static Set<String> backends(){return Set.of("auto","gl","rt");}
    public boolean request(String backend){
        if(!backends().contains(backend))return false;
        requested=backend;switchPending=true;
        ClientFrameCapture.INSTANCE.invalidate("Storm simulation backend changed");
        return true;
    }
    public void invalidate(){resetGl=true;}
    public void beforeStormActivation(AllayStormRuntime storm){
        applyChange(storm);
        if(!storm.active()&&nativeSelected&&NativeStormSimulation.selected()==null)new NativeStormSimulation(storm);
    }
    public void poll(){
        NativeStormSimulation.serviceFrame();
        applyChange(AllayStormRuntime.INSTANCE);
        if(resetGl){closeGl();resetGl=false;}
        RtQueryWorker.retire();
    }
    private void applyChange(AllayStormRuntime storm){
        if(storm.active())return;
        var owner=NativeStormSimulation.selected();
        if(owner!=null){
            if(!owner.readyToDetach(switchPending))return;
            owner.retire();
        }
        if(!switchPending)return;
        nativeSelected=!requested.equals("gl");
        java.util.Arrays.fill(glWork,0L);
        resetGl=true;switchPending=false;
    }
    public SpatialScene begin(long tick,float time,long resourceEpoch){
        if(nativeSelected)return null;
        if(scene==null)scene=new SpatialScene();
        scene.begin(tick,time,resourceEpoch);return scene;
    }
    public String identity(){
        var owner=NativeStormSimulation.selected();
        String active=!nativeSelected?"gl":owner==null?"rt-idle":!owner.failure().isEmpty()?"rt-failed":
                owner.running()?"rt":"rt-waiting";
        return "requested="+requested+" active="+active+" abi=12"+(switchPending?" switch=pending":"");
    }
    public SpatialScene scene(){return scene;}
    /** Actual GL work submission sites. LBVH query counts alone do not measure fallback. */
    public void submitted(GlWork stage){glWork[stage.ordinal()]++;}
    public String status(){
        var counts=new StringBuilder("GL Storm submissions:");
        for(var stage:GlWork.values())counts.append(' ').append(stage.name().toLowerCase(java.util.Locale.ROOT)).append('=').append(glWork[stage.ordinal()]);
        var owner=NativeStormSimulation.selected();
        String failure=owner==null?"":owner.failure();
        return identity()+"\n"+(scene==null?"gl_queries=0":scene.description())+"\n"+counts+
                (failure.isEmpty()?"":"\nfailure="+failure);
    }
    private void closeGl(){if(scene!=null)scene.close();scene=null;}
    @Override public void close(){
        closeGl();
        var owner=NativeStormSimulation.selected();
        if(owner!=null)owner.retire();
    }
}
