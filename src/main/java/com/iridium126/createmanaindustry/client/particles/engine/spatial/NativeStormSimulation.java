package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import com.iridium126.createmanaindustry.client.allaystorm.AllayStormRuntime;
import com.iridium126.createmanaindustry.client.allaystorm.ColumnSnapshotClient;
import com.iridium126.createmanaindustry.client.allaystorm.StormFeedback;
import java.io.IOException;
import java.util.ArrayList;

/** Native simulation owner for a client Storm lifetime. Render frames service receipts and display leases. */
public final class NativeStormSimulation {
    public interface FrozenDisplay extends AutoCloseable {
        int buffer();
        long tick();
        long gameTime();
        long sequence();
        long identity();
        @Override void close();
    }

    private static final ArrayList<NativeStormSimulation> RETIRING=new ArrayList<>();
    private static NativeStormSimulation selected;
    private final AllayStormRuntime storm;
    private final StormNativeInputs inputs=new StormNativeInputs();
    private NativeStormControl control;
    private boolean retired;

    NativeStormSimulation(AllayStormRuntime storm){
        if(selected!=null)throw new IllegalStateException("A native Storm already owns simulation");
        this.storm=storm;
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
    private void acceptPositions(NativeStormControl.PositionFeedback batch){
        if(batch.identity()==inputs.generation())
            StormFeedback.INSTANCE.acceptPositionSnapshot(storm.identity(),batch.tick(),batch.rows());
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
                control=new NativeStormControl(ParticleRtModules.prepare(),SpatialScene.CAPACITY,inputs.journal(),
                        this::acceptRegional,this::acceptColumn,this::acceptOuter,this::acceptPositions,this::acceptSnapshot);
            }catch(IOException|RuntimeException|LinkageError failure){
                inputs.stop(failure.toString());
                com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.warn("[CMI particles] Native Storm initialization failed",failure);
            }
        }
        if(control!=null)try(var timing=com.iridium126.createmanaindustry.client.particles.engine.ParticleFrameStages.begin("rt.storm.service")){
            control.service();
        }
    }
    public FrozenDisplay display(){return retired||control==null?null:control.display(inputs.generation());}
    public void displayCommitted(long identity,long sequence){inputs.displayCommitted(identity,sequence);}
    public String failure(){return inputs.failure().isEmpty()&&control!=null?control.failure():inputs.failure();}
    boolean running(){return control!=null&&failure().isEmpty();}
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
