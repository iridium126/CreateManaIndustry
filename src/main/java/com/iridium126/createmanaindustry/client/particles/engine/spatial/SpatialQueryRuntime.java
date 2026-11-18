package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import com.iridium126.createmanaindustry.client.allaystorm.AllayStormRuntime;
import com.iridium126.createmanaindustry.client.particles.engine.ClientFrameCapture;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import com.iridium126.createmanaindustry.infrastructure.config.StormSimulationBackend;
import com.iridium126.createmanaindustry.infrastructure.config.StormSimulationGpu;
import java.util.Locale;

/** Selects the Storm simulation owner at an identity boundary. */
public final class SpatialQueryRuntime implements AutoCloseable {
    public static final SpatialQueryRuntime INSTANCE=new SpatialQueryRuntime();
    private ClientConfig.StormSimulation selected=new ClientConfig.StormSimulation(
            StormSimulationBackend.AUTO,StormSimulationGpu.FOLLOW_MINECRAFT);
    private String fallbackReason="";
    private boolean resetGl;
    private SpatialScene scene;
    public enum GlWork { STORM_STEP, COLUMN_RECOVERY, DOME_CONTACT, DEPENETRATION, NAVIGATION, ATTACK_BATCH }
    private final long[] glWork=new long[GlWork.values().length];
    private SpatialQueryRuntime(){}
    private boolean nativeSelected(){return selected.backend()==StormSimulationBackend.RT
            || selected.backend()==StormSimulationBackend.AUTO&&fallbackReason.isEmpty();}
    public void invalidate(){resetGl=true;}
    public void beforeStormActivation(AllayStormRuntime storm){
        applyChange(storm);
        if(!storm.active()&&nativeSelected()&&NativeStormSimulation.selected()==null)new NativeStormSimulation(storm,selected.gpu());
    }
    public void poll(){
        NativeStormSimulation.serviceFrame();
        var storm=AllayStormRuntime.INSTANCE;
        var owner=NativeStormSimulation.selected();
        if(selected.backend()==StormSimulationBackend.AUTO&&owner!=null&&!owner.failure().isEmpty()){
            fallbackReason=owner.failure();
            storm.recoverGlSimulation();
            com.iridium126.createmanaindustry.client.particles.engine.ParticleRuntime.INSTANCE.invalidateStormTargetEdge();
            com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.breakStormDisplayHistory();
            owner.retire();
            resetGl=true;
            ClientFrameCapture.INSTANCE.invalidate("AUTO switched Storm simulation to GL");
        }
        applyChange(storm);
        if(resetGl){closeGl();resetGl=false;}
        RtQueryWorker.retire();
    }
    private void applyChange(AllayStormRuntime storm){
        if(storm.active())return;
        var owner=NativeStormSimulation.selected();
        var requested=ClientConfig.stormSimulation;
        boolean changed=!selected.equals(requested);
        if(owner!=null){
            if(!owner.readyToDetach(changed))return;
            owner.retire();
        }
        if(!changed)return;
        selected=requested;
        fallbackReason="";
        java.util.Arrays.fill(glWork,0L);
        resetGl=true;
        ClientFrameCapture.INSTANCE.invalidate("Storm simulation backend or GPU changed");
    }
    public SpatialScene begin(long tick,float time,long resourceEpoch){
        if(nativeSelected())return null;
        if(scene==null)scene=new SpatialScene();
        scene.begin(tick,time,resourceEpoch);return scene;
    }
    public String identity(){
        var owner=NativeStormSimulation.selected();
        String active=!nativeSelected()?"gl":owner==null?"rt-idle":!owner.failure().isEmpty()?"rt-failed":
                owner.running()?"rt":"rt-waiting";
        var requested=ClientConfig.stormSimulation;
        return "requested="+requested.backend().name().toLowerCase(Locale.ROOT)+" active="+active+" abi=13"
                +(!selected.equals(requested)?" switch=pending":"");
    }
    public SpatialScene scene(){return scene;}
    /** Actual GL work submission sites. LBVH query counts alone do not measure fallback. */
    public void submitted(GlWork stage){glWork[stage.ordinal()]++;}
    public String status(){
        var counts=new StringBuilder("GL Storm submissions:");
        for(var stage:GlWork.values())counts.append(' ').append(stage.name().toLowerCase(java.util.Locale.ROOT)).append('=').append(glWork[stage.ordinal()]);
        var owner=NativeStormSimulation.selected();
        String failure=owner==null&&selected.backend()==StormSimulationBackend.AUTO?fallbackReason:owner==null?"":owner.failure();
        String renderer=StormGpuDevices.renderer();
        var choice=ClientConfig.stormSimulation.gpu();
        String requestedGpu=choice.followsMinecraft()?renderer+" (Minecraft)":
                (choice.label().isEmpty()?choice.uuid():choice.label()+" ("+choice.uuid()+")");
        String actualGpu=!nativeSelected()?renderer:owner==null||owner.simulationGpu().isEmpty()?"none":owner.simulationGpu();
        String transport=!nativeSelected()?"gl-local":owner==null?"none":owner.displayTransport();
        return identity()+"\nrender_gpu="+renderer+" requested_gpu="+requestedGpu+" simulation_gpu="+actualGpu+
                " display_transport="+transport+"\n"+(scene==null?"gl_queries=0":scene.description())+"\n"+counts+
                (failure.isEmpty()?"":"\nfailure="+failure);
    }
    private void closeGl(){if(scene!=null)scene.close();scene=null;}
    @Override public void close(){
        closeGl();
        fallbackReason="";
        var owner=NativeStormSimulation.selected();
        if(owner!=null)owner.retire();
    }
}
