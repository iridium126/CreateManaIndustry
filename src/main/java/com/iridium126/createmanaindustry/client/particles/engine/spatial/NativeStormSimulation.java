package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import com.iridium126.createmanaindustry.client.allaystorm.AllayStormRuntime;
import com.iridium126.createmanaindustry.client.allaystorm.ColumnSnapshotClient;
import com.iridium126.createmanaindustry.client.allaystorm.StormFeedback;
import com.iridium126.createmanaindustry.infrastructure.config.StormSimulationGpu;
import java.io.IOException;
import java.util.ArrayList;
import com.iridium126.createmanaindustry.infrastructure.output.CMILog;

/** Native simulation owner for a client Storm lifetime. Render frames service receipts and display leases. */
public final class NativeStormSimulation {
    public interface FrozenDisplay extends AutoCloseable {
        int buffer();
        /** Monotonic input tick used by GPU playback history. */
        long tick();
        /** Server-correctable world time, retained for gameplay and attack poses. */
        long gameTime();
        long sequence();
        long identity();
        default long epoch(){return 0;}
        default long completedNanos(){return 0;}
        @Override void close();
    }

    private static final ArrayList<NativeStormSimulation> RETIRING=new ArrayList<>();
    private static NativeStormSimulation selected;
    private final AllayStormRuntime storm;
    private final StormSimulationGpu gpu;
    private final StormNativeInputs inputs=new StormNativeInputs();
    private NativeStormControl control;
    private boolean retired;

    NativeStormSimulation(AllayStormRuntime storm,StormSimulationGpu gpu){
        if(selected!=null)throw new IllegalStateException("A native Storm already owns simulation");
        this.storm=storm;
        this.gpu=gpu;
        storm.attachNativeInputs(inputs);
        selected=this;
    }
    private void acceptRegional(NativeStormControl.RegionalFeedback batch){
        if(batch.identity()!=inputs.generation()||storm.identity()==null)return;
        var ref=batch.reference();
        var hit=batch.contact();
        StormFeedback.INSTANCE.acceptNativeRegional(batch.tick(),batch.id(),batch.ready(),batch.completed(),
                batch.blocked(),batch.unknown(),batch.flows(),ref==null?0:ref.version(),ref==null?0:ref.seconds(),
                hit==null?null:new StormFeedback.NativeHit(hit.target(),hit.x(),hit.y(),hit.z(),hit.time()));
    }
    private void acceptColumn(NativeStormControl.ColumnFeedback batch){
        if(batch.identity()!=inputs.generation()||storm.identity()==null)return;
        var sound=batch.sound();
        var hit=batch.contact();
        StormFeedback.INSTANCE.acceptNativeColumn(batch.tick(),batch.id(),batch.token(),batch.completed(),batch.installed(),
                sound==null?null:new net.minecraft.world.phys.Vec3(sound.x(),sound.y(),sound.z()),
                hit==null?null:new StormFeedback.NativeHit(hit.target(),hit.x(),hit.y(),hit.z(),hit.time()));
    }
    public static NativeStormSimulation selected(){return selected;}
    public static void prepareCapturedInputs(){
        var owner=selected;
        if(owner!=null&&owner.control!=null)owner.control.prepareInputs();
    }
    private void acceptPositions(NativeStormControl.PositionFeedback batch){
        if(batch.identity()==inputs.generation())
            StormFeedback.INSTANCE.acceptPositionSnapshot(storm.identity(),batch.tick(),batch.rows());
    }
    private void acceptFeathers(NativeStormControl.FeatherFeedback batch){
        if(batch.identity()==inputs.generation()&&storm.identity()!=null)
            com.iridium126.createmanaindustry.client.particles.engine.AllayFeatherGpu.INSTANCE.acceptNativeImpacts(storm.identity(),batch.rows());
    }
    private void acceptSnapshot(NativeStormControl.SnapshotFeedback batch){
        if(batch.identity()==inputs.generation())
            ColumnSnapshotClient.INSTANCE.publishCapture(batch.request(),batch.tick(),batch.seconds(),batch.values());
    }
    private void acceptOuter(NativeStormControl.OuterFeedback batch){
        if(batch.identity()!=inputs.generation()||storm.identity()==null)return;
        var flights=new ArrayList<com.iridium126.createmanaindustry.content.allaystorm.StormOuterLoop.Flight>();
        int[] rows=batch.rows();
        for(int i=0;i<rows.length;i+=2){
            int member=rows[i],cycle=rows[i+1];
            if(inputs.outerExpected(member,cycle))
                flights.add(new com.iridium126.createmanaindustry.content.allaystorm.StormOuterLoop.Flight(member,cycle,0,0,0));
        }
        StormFeedback.INSTANCE.acceptNativeOuter(flights);
    }
    public static void serviceFrame(){
        if(selected!=null)selected.service();
        RETIRING.removeIf(simulation->simulation.control.retire());
    }
    private void service(){
        if(control==null&&inputs.failure().isEmpty()&&inputs.journal().retained()>0){
            try{
                control=new NativeStormControl(ParticleRtModules.prepare(),SpatialScene.CAPACITY,gpu,inputs.journal(),
                        this::acceptRegional,this::acceptColumn,this::acceptOuter,this::acceptPositions,this::acceptSnapshot,this::acceptFeathers);
            }catch(IOException|RuntimeException|LinkageError failure){
                inputs.stop(failure.toString());
                com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.warn(CMILog.PARTICLES + "Native Storm initialization failed",failure);
            }
        }
        if(control!=null)try(var timing=com.iridium126.createmanaindustry.client.particles.engine.ParticleFrameStages.begin("rt.storm.service")){
            control.service(inputs.generation());
        }
    }
    public double displayTick(float partialTick){return inputs.displayTick(partialTick);}
    public FrozenDisplay display(){return retired||control==null?null:control.display(inputs.generation());}
    public void drainDisplays(java.util.function.Consumer<FrozenDisplay> sink){
        if(retired||control==null)return;
        // The early service starts CUDA work before GL simulation. Poll again at
        // the display deadline to pick up work completed during that simulation.
        service();
        control.drainDisplays(inputs.generation(),sink);
    }
    public void displayCommitted(long identity,long sequence){inputs.displayCommitted(identity,sequence);}
    public String failure(){return inputs.failure().isEmpty()&&control!=null?control.failure():inputs.failure();}
    boolean running(){return control!=null&&failure().isEmpty();}
    String simulationGpu(){return control==null?"":control.simulationGpu();}
    String displayTransport(){return control==null?"none":control.displayTransport();}
    boolean readyToDetach(boolean explicitSwitch){
        return !storm.active()&&(!inputs.hasPendingWork()||explicitSwitch&&!failure().isEmpty());
    }
    /** Explicit backend change or world shutdown. Already accepted native work continues retiring. */
    public void retire(){
        if(retired)return;
        retired=true;
        if(selected==this)selected=null;
        storm.detachNativeInputs(inputs);
        if(control!=null)RETIRING.add(this);
    }
}
