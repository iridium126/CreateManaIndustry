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
    private boolean resetOpenGl;
    private OpenGlStormScene scene;
    public enum HardwarePhase {
        IDLE, STARTING, RUNNING, FAILED
    }
    public record HardwareStatus(HardwarePhase phase, ClientConfig.StormSimulation requested, String renderer,
                                 String simulationGpu, String api, String transport, boolean pending, String failure,
                                 String diagnostics) {}
    private SpatialQueryRuntime(){}
    private boolean nativeSelected(){return selected.backend()!=StormSimulationBackend.OPENGL && fallbackReason.isEmpty();}
    public void invalidate(){resetOpenGl=true;}
    public void beforeStormActivation(AllayStormRuntime storm){
        applyChange(storm);
        if(!storm.active()&&nativeSelected()&&NativeStormSimulation.selected()==null)new NativeStormSimulation(storm,selected);
    }
    public void poll(){
        NativeStormSimulation.serviceFrame();
        var storm=AllayStormRuntime.INSTANCE;
        var owner=NativeStormSimulation.selected();
        if(selected.backend()==StormSimulationBackend.AUTO&&owner!=null&&!owner.failure().isEmpty()){
            fallbackReason=owner.failure();
            storm.recoverOpenGlSimulation();
            com.iridium126.createmanaindustry.client.particles.engine.ParticleRuntime.INSTANCE.invalidateStormTargetEdge();
            com.iridium126.createmanaindustry.client.render.scene.ClientGpuScene.INSTANCE.breakStormDisplayHistory();
            owner.retire();
            resetOpenGl=true;
            ClientFrameCapture.INSTANCE.invalidate("AUTO switched Storm simulation to OpenGL");
        }
        applyChange(storm);
        if(resetOpenGl){closeOpenGl();resetOpenGl=false;}
        OptixQueryWorker.retire();
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
        resetOpenGl=true;
        ClientFrameCapture.INSTANCE.invalidate("Storm simulation backend or GPU changed");
    }
    public OpenGlStormScene begin(long tick,float time,long resourceEpoch){
        if(nativeSelected())return null;
        if(scene==null)scene=new OpenGlStormScene();
        scene.begin(tick,time,resourceEpoch);return scene;
    }
    public String identity(){
        var owner=NativeStormSimulation.selected();
        var requested=ClientConfig.stormSimulation;
        return identity(requested,phase(owner),simulationApi(owner),!selected.equals(requested));
    }
    private HardwarePhase phase(NativeStormSimulation owner){
        if(!nativeSelected())return HardwarePhase.RUNNING;
        if(owner==null)return HardwarePhase.IDLE;
        if(!owner.failure().isEmpty())return HardwarePhase.FAILED;
        return owner.running()?HardwarePhase.RUNNING:HardwarePhase.STARTING;
    }
    private String simulationApi(NativeStormSimulation owner){
        return !nativeSelected()?"opengl":owner==null?"none":owner.simulationApi();
    }
    private static String identity(ClientConfig.StormSimulation requested,HardwarePhase phase,String api,boolean pending){
        return "requested="+requested.backend().name().toLowerCase(Locale.ROOT)
                +" active="+phase.name().toLowerCase(Locale.ROOT)
                +" abi="+com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.ABI
                +" simulation_api="+api
                +(pending?" switch=pending":"");
    }
    public OpenGlStormScene scene(){return scene;}
    public String status(){return hardwareStatus().diagnostics();}
    public HardwareStatus hardwareStatus(){
        var owner=NativeStormSimulation.selected();
        var phase=phase(owner);
        var requested=ClientConfig.stormSimulation;
        boolean pending=!selected.equals(requested);
        String api=simulationApi(owner);
        String failure=owner==null?fallbackReason:owner.failure();
        String renderer=StormGpuDevices.renderer();
        var choice=requested.gpu();
        String requestedGpu=choice.followsMinecraft()?renderer+" (Minecraft)":
                (choice.label().isEmpty()?choice.uuid():choice.label()+" ("+choice.uuid()+")");
        boolean openGl=api.equals("opengl");
        String actualGpu=openGl?renderer:owner==null?"":owner.simulationGpu();
        if(actualGpu.isEmpty()&&!openGl)actualGpu="none";
        String transport=openGl?"opengl-local":owner==null?"none":owner.displayTransport();
        String diagnostics=identity(requested,phase,api,pending)+"\nrender_gpu="+renderer+" requested_gpu="+requestedGpu+" simulation_gpu="+actualGpu+
                " display_transport="+transport+(scene==null?"":"\n"+scene.description())+
                (failure.isEmpty()?"":"\nfailure="+failure);
        return new HardwareStatus(phase,requested,renderer,actualGpu,api,transport,pending,failure,diagnostics);
    }
    private void closeOpenGl(){if(scene!=null)scene.close();scene=null;}
    @Override public void close(){
        closeOpenGl();
        fallbackReason="";
        var owner=NativeStormSimulation.selected();
        if(owner!=null)owner.retire();
    }
}
