package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import com.iridium126.createmanaindustry.client.allaystorm.StormCollisionRuntime;
import com.iridium126.createmanaindustry.client.allaystorm.ColumnSnapshotClient;
import com.iridium126.createmanaindustry.content.allaystorm.AllayStarfall;
import com.iridium126.createmanaindustry.content.allaystorm.DomeTemplate;
import com.iridium126.createmanaindustry.content.allaystorm.HelixGatherFrame;
import com.iridium126.createmanaindustry.content.allaystorm.HelixGatherRecovery;
import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormColumnPacket;
import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket;
import com.iridium126.createmanaindustry.content.allaystorm.network.StormColumnSnapshotPacket;
import com.iridium126.createmanaindustry.content.allaystorm.network.StormOuterPacket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import static com.iridium126.createmanaindustry.client.gpu.GpuResourceConstants.*;

/** Accepted client events, held independently of the render thread's native work slots. */
public final class StormNativeInputs {
    private final StormControlJournal journal=new StormControlJournal();
    private final SpatialTerrain spatialTerrain=new SpatialTerrain(SpatialTerrain.COLLISION);
    private final ArrayList<StormControlJournal.Command> pending=new ArrayList<>();
    private final ClientboundStormWavePacket[] helixPackets=new ClientboundStormWavePacket[STORM_DOME_SLOTS];
    private final HelixGatherRecovery[] helixRecovery=new HelixGatherRecovery[STORM_DOME_SLOTS];
    private final int[] outerCycles=new int[131072];
    private int outerCount;
    private long generation=1, terrainVersion=Long.MIN_VALUE, projectedSequence;
    private long lastPositionSnapshot=Long.MIN_VALUE;
    private long geometryRevision=Long.MIN_VALUE;
    private Object terrainSource, previousTarget;
    private Vec3 previousAim;
    private boolean active, needsDome;
    private String failure="";

    StormControlJournal journal(){return journal;}
    long generation(){return generation;}
    String failure(){return failure;}
    void displayCommitted(long identity,long sequence){
        if(identity==generation)projectedSequence=Math.max(projectedSequence,sequence);
    }
    public void stop(String reason){if(failure.isEmpty())failure=reason;journal.stop();}
    public void lifecycle(boolean active){
        generation=Math.incrementExact(generation);
        this.active=active;
        projectedSequence=0;
        lastPositionSnapshot=Long.MIN_VALUE;
        needsDome=false;
        invalidateTargetEdge();
        Arrays.fill(helixPackets,null);
        Arrays.fill(helixRecovery,null);
        Arrays.fill(outerCycles,0);
        outerCount=0;
        add(StormControlJournal.Command.lifecycle(active));
    }
    public void invalidateTargetEdge(){previousTarget=null;previousAim=null;}
    private void add(StormControlJournal.Command command){pending.add(command.inGeneration(generation));}
    private void stampFrom(int first){
        for(int i=first;i<pending.size();i++)pending.set(i,pending.get(i).inGeneration(generation));
    }
    public boolean spawn(int first,int count,boolean growth){
        if(!active)return false;
        add(StormControlJournal.Command.spawn(first,count,growth));
        return true;
    }
    public void damage(int member,float damage,float kbX,float kbZ,float light,boolean died){
        if(active)add(StormControlJournal.Command.damage(member,damage,kbX,kbZ,light,died));
    }
    public void correction(int member,float x,float y,float z,float arrival,float vx,float vy,float vz,float strength){
        if(active)add(StormControlJournal.Command.correction(member,
                new StormControlJournal.Float4(x,y,z,arrival),new StormControlJournal.Float4(vx,vy,vz,strength)));
    }
    public boolean hasOuter(){return outerCount!=0;}
    boolean outerExpected(int member,int cycle){
        return member>=0&&member<outerCycles.length&&outerCycles[member]==cycle;
    }
    public void outer(StormOuterPacket packet){
        if(!active||packet.operation()==StormOuterPacket.ARRIVED)return;
        for(int i=0;i<packet.members().length;i++){
            int member=packet.members()[i], cycle=packet.cycles()[i];
            if(packet.operation()==StormOuterPacket.START){
                if(outerCycles[member]==cycle)continue;
                if(outerCycles[member]==0)outerCount++;
                outerCycles[member]=cycle;
                float[] xyz=packet.targets();
                add(StormControlJournal.Command.outer(member,cycle,new StormControlJournal.Float4(
                        xyz[i*3],xyz[i*3+1],xyz[i*3+2],0)));
            }else if(outerExpected(member,cycle)){
                outerCycles[member]=0;
                outerCount--;
                add(StormControlJournal.Command.outer(member,cycle,null));
            }
        }
    }
    public void attack(int slot,ClientboundStormWavePacket packet,boolean replacement,
                       ClientboundStormColumnPacket column,float localSeconds){
        if(!active)return;
        if(packet.column()){
            if(column==null)throw new IllegalArgumentException("Column command requires its accepted packet");
            int columnSlot=slot-STORM_DOME_SLOTS;
            add(StormControlJournal.Command.columnState(columnSlot,column,replacement));
            if(replacement&&!packet.abort())for(int rank=0;rank<packet.members().length;rank++)
                add(StormControlJournal.Command.columnAssign(packet.waveId(),columnSlot,packet.members()[rank],rank));
            return;
        }
        if(packet.dome()){
            if(packet.abort()){add(StormControlJournal.Command.domeCancel(packet.waveId(),slot));return;}
            needsDome=true;
            var center=new StormControlJournal.Float4(packet.centerX(),packet.centerY(),packet.centerZ(),packet.orientation());
            add(StormControlJournal.Command.domeState(packet.waveId(),slot,packet.locked()?2:1,replacement,center,
                    packet.startSec(),elapsed(packet.lockSec(),packet),elapsed(packet.diveUntilSec(),packet),packet.crownRadius()));
            for(int flow=0;flow<STORM_DOME_FLOWS;flow++){
                float pause=packet.gatherFlows().pausedAt(flow);
                if(pause>=0)add(StormControlJournal.Command.domePause(packet.waveId(),slot,flow,pause));
            }
            if(replacement)for(int rank=0;rank<packet.members().length;rank++)
                add(StormControlJournal.Command.domeAssign(packet.waveId(),slot,packet.members()[rank],rank));
        }else if(packet.helixAttack()){
            if(packet.abort()){
                add(StormControlJournal.Command.helixCancel(packet.waveId(),slot));
                helixPackets[slot]=null;helixRecovery[slot]=null;
                return;
            }
            var frames=packet.gatherFrames();
            var flight=packet.helix();
            add(StormControlJournal.Command.helixState(packet.waveId(),slot,packet.locked()?2:1,replacement,frames.length,
                    packet.startSec(),elapsed(flight.thrust(),packet),elapsed(packet.diveUntilSec(),packet),
                    Math.max(0,elapsed(packet.centerSec(),packet)),flight.speed(),flight.maxSpeed(),flight.targetArc(),true));
            int first=pending.size();
            StormControlJournal.Command.helixFrames(pending,packet.waveId(),slot,frames);
            var old=helixPackets[slot];
            var recovery=helixRecovery[slot];
            if(replacement||packet.locked()||old==null||old.gatherFrames().length==0||frames.length==0)recovery=null;
            else{
                float seconds=Math.max(0,elapsed(localSeconds,packet)-.25f);
                var previous=old.gatherFrames()[old.gatherFrames().length-1];
                if(frames[frames.length-1].version()!=previous.version()&&seconds>previous.seconds()+.251f){
                    var before=sample(old.gatherFrames(),seconds);
                    if(recovery!=null)before=recovery.apply(before,seconds);
                    recovery=HelixGatherRecovery.between(seconds,before,sample(frames,seconds));
                }
            }
            if(recovery!=helixRecovery[slot]||replacement)
                StormControlJournal.Command.helixRecovery(pending,packet.waveId(),slot,recovery);
            stampFrom(first);
            helixPackets[slot]=packet;helixRecovery[slot]=recovery;
            if(replacement)for(int rank=0;rank<packet.members().length;rank++)
                add(StormControlJournal.Command.helixAssign(packet.waveId(),slot,packet.members()[rank],rank));
        }
    }
    private static float elapsed(float time,ClientboundStormWavePacket packet){return AllayStarfall.elapsed(time,packet.startSec());}
    private static HelixGatherFrame.Sample sample(HelixGatherFrame[] frames,float seconds){
        var selected=frames[0];
        for(var frame:frames){if(frame.seconds()>seconds)break;selected=frame;}
        return selected.sample(seconds);
    }
    public void release(int slot,ClientboundStormWavePacket packet,BitSet ranks){
        if(!active)return;
        for(int rank=ranks.nextSetBit(0);rank>=0&&rank<packet.members().length;rank=ranks.nextSetBit(rank+1)){
            int member=packet.members()[rank];
            if(packet.column())add(StormControlJournal.Command.columnRelease(packet.waveId(),slot-STORM_DOME_SLOTS,member));
            else if(packet.dome()||packet.helixAttack())add(StormControlJournal.Command.domeRelease(packet.waveId(),slot,member));
        }
    }
    public void restore(int slot,ClientboundStormWavePacket attack,StormColumnSnapshotPacket snapshot){
        if(!active)return;
        int first=pending.size();
        StormControlJournal.Command.columnRestore(pending,attack.waveId(),slot-STORM_DOME_SLOTS,snapshot.token(),
                elapsed(snapshot.sampleSec(),attack),snapshot.snapshot());
        stampFrom(first);
    }

    boolean hasPendingWork(){return !pending.isEmpty()||journal.retained()>0;}
    public boolean due(){return failure.isEmpty()&&(active||!pending.isEmpty());}
    public void capture(long gameTime,int population,int seed,Vec3 center,float radius,float rotation,float conveyor,
                        float conveyorRate,float omega,float seconds,float speed,Object target,int targetId,Vec3 aim,
                        List<? extends Player> players,double correctionHz){
        if(!due())return;
        long tick=journal.nextLogicalTick();
        var terrain=terrainRevision();
        var p=active?new StormControlJournal.Parameters(generation,true,population,seed,(float)center.x,(float)center.y,
                (float)center.z,Math.max(2,radius),rotation,conveyor,conveyorRate,omega,seconds,speed,needsDome&&DomeTemplate.keysReady()):
                StormControlJournal.Parameters.inactive(generation,seconds);
        var contact=StormControlJournal.Target.ABSENT;
        if(active&&target!=null&&targetId>0&&aim!=null){
            var previous=previousTarget==target?previousAim:aim;
            contact=new StormControlJournal.Target(targetId,(float)previous.x,(float)previous.y,(float)previous.z,
                    (float)aim.x,(float)aim.y,(float)aim.z);
        }
        var samples=new ArrayList<StormControlJournal.Float4>(active?players.size():0);
        if(active)for(var player:players){
            if(player.isAlive()&&!player.isSpectator())
                samples.add(new StormControlJournal.Float4((float)player.getX(),
                        (float)player.getBoundingBox().getCenter().y,(float)player.getZ(),(float)player.getY()));
        }
        boolean positionSnapshot=population>0&&!samples.isEmpty()&&correctionHz>0
                &&(lastPositionSnapshot==Long.MIN_VALUE||tick-lastPositionSnapshot>=20.0/correctionHz);
        StormControlJournal.Geometry geometry=null;
        if(active&&journal.inFlight()<RT_SLOTS&&journal.queued()<RT_SLOTS){
            spatialTerrain.refresh();
            long revision=spatialTerrain.revision();
            if(revision!=geometryRevision){
                var rows=spatialTerrain.nativeRows();
                byte[] bytes=new byte[rows.remaining()];
                rows.get(bytes);
                geometry=new StormControlJournal.Geometry(revision,spatialTerrain.count(),bytes);
            }
        }
        var captures=active?ColumnSnapshotClient.INSTANCE.reserveNativeCaptures():List.<ColumnSnapshotClient.NativeCapture>of();
        journal.appendWorldSample(gameTime,p,pending,terrain,contact,projectedSequence,samples,positionSnapshot,geometry,captures);
        if(geometry!=null)geometryRevision=geometry.revision();
        if(positionSnapshot)lastPositionSnapshot=tick;
        pending.clear();
        previousTarget=aim==null?null:target;previousAim=aim;
    }
    private StormControlTerrain terrainRevision(){
        var source=StormCollisionRuntime.spatialSource();
        long version=StormCollisionRuntime.spatialVersion();
        if(terrainSource==source&&terrainVersion==version)return null;
        var packed=StormControlTerrain.capture(StormCollisionRuntime.spatialPages());
        terrainSource=source;terrainVersion=version;
        return packed;
    }
}
