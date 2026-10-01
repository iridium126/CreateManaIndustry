package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import com.simibubi.create.content.kinetics.chainConveyor.*;
import com.simibubi.create.content.logistics.box.PackageItem;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Immutable server-thread native qualification/geometry. Item stacks and address filters never
 * cross the client wire. Geometry is shared by every package on this conveyor link. */
public final class PackageNativeChainPlan {
    public static final int PORT=1,EXIT=2,END=4;
    public record Node(float threshold,int flags,BlockPos target,String filter) {}
    private record Route(String port,int distance,BlockPos next,boolean end) {}
    private final ChainConveyorBlockEntity conveyor;
    private final BlockPos connection;
    private final Map<BlockPos,ChainConveyorBlockEntity.ConnectedPort> ports;
    private final Set<BlockPos> connections;
    private final List<Route> routes;
    private final List<Node> nodes;
    private final PackageChainTrack track;
    private final int speedBits;
    public PackageNativeChainPlan(ChainConveyorBlockEntity conveyor,BlockPos connection,long revision) {
        this.conveyor=Objects.requireNonNull(conveyor);this.connection=connection;speedBits=Float.floatToIntBits(conveyor.getSpeed());
        // prepareStats outside native tick can consume a direction change before Create's
        // reversal pass sees it. Defer acquisition until that native pass has completed.
        if(conveyor.connectionStats==null || conveyor.getSpeed()!=0 && conveyor.reversed!=(conveyor.getSpeed()<0))
            throw new IllegalArgumentException("Create chain stats not prepared by native tick");
        connections=Set.copyOf(conveyor.connections);ports=Map.copyOf(connection==null?conveyor.loopPorts:conveyor.travelPorts);
        routes=conveyor.routingTable.entriesByDistance.stream().map(r->new Route(r.port(),r.distance(),r.nextConnection(),r.endOfRoute())).toList();
        var assembled=new ArrayList<Node>();
        // Preserve the order in which native Create tests ports, then exits/end.
        for(var entry:(connection==null?conveyor.loopPorts:conveyor.travelPorts).entrySet()) {
            var port=entry.getValue();if(connection!=null && !connection.equals(port.connection()))continue;
            assembled.add(new Node(port.chainPosition(),PORT,entry.getKey().immutable(),port.filter()));
        }
        if(connection==null)for(var exit:conveyor.connections) {
            var stats=conveyor.connectionStats.get(exit);if(stats==null)throw new IllegalArgumentException("Missing chain exit stats");
            assembled.add(new Node(stats.tangentAngle(),EXIT,exit.immutable(),""));
        } else {
            var stats=conveyor.connectionStats.get(connection);if(stats==null)throw new IllegalArgumentException("Missing chain travel stats");
            assembled.add(new Node(stats.chainLength(),END,connection.immutable(),""));
        }
        if(assembled.size()>32)throw new IllegalArgumentException("Native chain nodes exceed GPU mask");
        nodes=List.copyOf(assembled);var prepared=connection==null?PackageChainTrack.loop(conveyor.getBlockPos(),conveyor.getSpeed(),0,nodes.size(),revision)
                :PackageChainTrack.travel(conveyor,connection,0,nodes.size(),revision);
        track=new PackageChainTrack(prepared.start(),prepared.end(),prepared.radius(),prepared.length(),prepared.rate(),prepared.looping(),conveyor.reversed,prepared.yaw(),0,nodes.size(),revision);
    }
    public boolean current() {
        if(conveyor.isRemoved() || conveyor.getLevel()==null || conveyor.isVirtual() || speedBits!=Float.floatToIntBits(conveyor.getSpeed())
                || !connections.equals(conveyor.connections) || !ports.equals(connection==null?conveyor.loopPorts:conveyor.travelPorts))return false;
        var latest=conveyor.routingTable.entriesByDistance;if(latest.size()!=routes.size())return false;
        for(int i=0;i<routes.size();i++) {
            var a=routes.get(i);var b=latest.get(i);
            if(!a.port.equals(b.port()) || a.distance!=b.distance() || !a.next.equals(b.nextConnection()) || a.end!=b.endOfRoute())return false;
        }
        return connection==null || conveyor.connections.contains(connection);
    }
    public boolean contains(ChainConveyorPackage box) {
        var list=connection==null?conveyor.getLoopingPackages():conveyor.getTravellingPackages().get(connection);
        return list!=null && list.contains(box);
    }
    public int eligibility(ChainConveyorPackage box) {
        int mask=0;BlockPos exit=connection==null?conveyor.routingTable.getExitFor(box.item):null;
        for(int i=0;i<nodes.size();i++) {
            var node=nodes.get(i);boolean match=node.flags==PORT?PackageItem.matchAddress(box.item,node.filter):node.flags==END || node.target.equals(exit);
            if(match)mask|=1<<i;
        }
        return mask;
    }
    public PackageChainAuthority.State snapshot(ChainConveyorPackage box,long tick) {
        Vec3 position=box.worldPosition==null?position(box.chainPosition):box.worldPosition;float yaw=track.looping()?box.chainPosition+(track.reversed()?180:0):track.yaw();
        return new PackageChainAuthority.State(box.chainPosition,tick,new PackageLease.Pose(position.x,position.y,position.z,0,0,0,yaw));
    }
    private boolean crossed(float before,float after,float threshold) {
        return track.looping()?conveyor.loopThresholdCrossed(after,before,threshold):before<=threshold && after>=threshold;
    }
    private Vec3 position(float progress) {
        if(track.looping()){double radians=Math.toRadians(progress);return track.start().add(Math.sin(radians)*track.radius(),0,Math.cos(radians)*track.radius());}
        return track.start().add(track.end().subtract(track.start()).scale(track.length()>0?Math.min(track.length(),progress)/track.length():0));
    }
    public boolean validate(PackageChainAuthority.Event event,ChainConveyorPackage box) {
        if(!current() || !contains(box))return false;
        if(event.flags()==PackageChainEventCodec.FALLBACK)return true;
        if(track.rate()==0 || event.flags()!=((track.looping()?1:0)|(track.reversed()?2:0)) || Float.floatToIntBits(event.rate())!=Float.floatToIntBits(track.rate())
                || track.looping() && (event.before()>=360 || event.after()>=360)
                || !track.looping() && (event.before()>track.length() || event.after()>track.length()))return false;
        float moved=track.looping()?Mth.positiveModulo((event.after()-event.before())*(track.rate()<0?-1:1),360):event.after()-event.before();
        if(moved<0 || moved>Math.abs(track.rate())*.05f+1e-4f)return false;
        float ahead=track.looping()?Mth.positiveModulo(event.after()+track.rate()*.2f,360):Math.min(track.length(),event.after()+track.rate()*.2f);
        for(int i=0;i<nodes.size();i++) {
            int bit=1<<i;var node=nodes.get(i);
            if((event.actual()&bit)!=0 && !crossed(event.before(),event.after(),node.threshold))return false;
            if((event.ahead()&bit)!=0 && (node.flags!=PORT || !crossed(event.before(),ahead,node.threshold)))return false;
        }
        return true;
    }
    public boolean reachable(PackageChainAuthority.Baseline baseline,PackageChainAuthority.Event event,long tick) {
        if(event.flags()==PackageChainEventCodec.FALLBACK)return true;
        float distance=track.looping()?Mth.positiveModulo((event.after()-baseline.state().progress())*(track.rate()<0?-1:1),360):event.after()-baseline.state().progress();
        double allowed=Math.abs(track.rate())*(Math.max(0,tick-baseline.state().tick())+2)/20.0+1e-4;
        return distance>=0 && distance<=allowed;
    }
    /** On-demand picking may not skip a still-unconfirmed inventory/route crossing. */
    public PackageChainAuthority.State pickupCheckpoint(PackageChainAuthority.Baseline baseline,float progress,long tick) {
        return pickupCheckpoint(track,nodes,baseline,progress,tick);
    }
    /** Pure numerical qualification, shared with tests; no mutable world access. */
    public static PackageChainAuthority.State pickupCheckpoint(PackageChainTrack track,List<Node> nodes,PackageChainAuthority.Baseline baseline,float progress,long tick) {
        if(!Float.isFinite(progress) || progress<0 || tick<baseline.state().tick()
                || (track.looping()?progress>=360:progress>track.length()))return null;
        float direction=track.rate()<0?-1:1,before=baseline.state().progress();
        float distance=track.looping()?Mth.positiveModulo((progress-before)*direction,360):progress-before;
        double allowed=Math.abs(track.rate())*(tick-baseline.state().tick()+2)/20.0+1e-4;
        if(distance<0 || distance>allowed)return null;
        for(int i=0;i<nodes.size();i++)if((baseline.eligibility()&(1<<i))!=0) {
            float nodeDistance=track.looping()?Mth.positiveModulo((nodes.get(i).threshold-before)*direction,360):nodes.get(i).threshold-before;
            if(nodeDistance>0 && distance>nodeDistance+1e-4)return null;
        }
        Vec3 p;
        if(track.looping()){double angle=Math.toRadians(progress);p=track.start().add(Math.sin(angle)*track.radius(),0,Math.cos(angle)*track.radius());}
        else p=track.start().add(track.end().subtract(track.start()).scale(track.length()>0?progress/track.length():0));
        float yaw=track.looping()?progress+(track.reversed()?180:0):track.yaw();
        return new PackageChainAuthority.State(progress,tick,new PackageLease.Pose(p.x,p.y,p.z,0,0,0,yaw));
    }
    public void removePicked(ChainConveyorPackage box,PackageChainAuthority.State state) {
        box.chainPosition=state.progress();var p=state.pose();box.worldPosition=new Vec3(p.x(),p.y(),p.z());box.yaw=p.yaw();remove(box);
    }
    /** Native methods own item movement. Caller claims the transaction before invoking this. */
    public boolean commit(PackageChainAuthority.Event event,ChainConveyorPackage box) {
        box.chainPosition=event.after();box.worldPosition=position(box.chainPosition);
        var access=(PackageChainNativeAccess)conveyor;var level=conveyor.getLevel();
        for(int i=0;i<nodes.size();i++) {
            int bit=1<<i;var node=nodes.get(i);
            if(node.flags==PORT && PackageItem.matchAddress(box.item,node.filter)) {
                if((event.ahead()&bit)!=0)access.cmi$anticipatePort(node.target);
                if((event.actual()&bit)!=0 && access.cmi$exportToPort(box,node.target)) {
                    remove(box);conveyor.notifyUpdate();return true;
                }
            } else if((event.actual()&bit)!=0 && node.flags==EXIT && conveyor.routingTable.getExitFor(box.item).equals(node.target)) {
                var destination=level.getBlockEntity(conveyor.getBlockPos().offset(node.target));
                if(destination instanceof ChainConveyorBlockEntity other && !other.canAcceptMorePackagesFromOtherConveyor())continue;
                box.chainPosition=0;
                if(conveyor.addTravellingPackage(box,node.target)){remove(box);return true;}
            } else if((event.actual()&bit)!=0 && node.flags==END) {
                if(!(level.getBlockEntity(conveyor.getBlockPos().offset(connection)) instanceof ChainConveyorBlockEntity other))return true;
                var stats=conveyor.connectionStats.get(connection);
                box.chainPosition=Mth.positiveModulo(stats.tangentAngle()+180+70*(track.reversed()?-1:1),360);
                if(other.addLoopingPackage(box)){remove(box);conveyor.notifyUpdate();return true;}
            }
        }
        return false;
    }
    private void remove(ChainConveyorPackage box) {
        var list=connection==null?conveyor.getLoopingPackages():conveyor.getTravellingPackages().get(connection);
        if(list==null || !list.remove(box))throw new IllegalStateException("Native chain transaction lost its package");
    }
    /** Only explicit interaction/save/fallback visits a package. Clamp unconfirmed crossings,
     * so a save or pickup cannot extrapolate past a server-owned routing transaction. */
    public void materialize(ChainConveyorPackage box,PackageChainAuthority.Baseline baseline,long tick,boolean active) {
        float before=baseline.state().progress();float elapsed=active?Math.max(0,tick-baseline.state().tick())/20f:0;
        float distance=track.rate()*elapsed;
        if(track.looping()) {
            float direction=track.rate()<0?-1:1,limit=Math.abs(distance);
            for(int i=0;i<nodes.size();i++)if((baseline.eligibility()&(1<<i))!=0) {
                float d=Mth.positiveModulo((nodes.get(i).threshold-before)*direction,360);
                if(d>0 && d<limit)limit=d;
            }
            box.chainPosition=Mth.positiveModulo(before+direction*limit,360);
        } else {
            float after=Math.min(track.length(),before+distance);
            for(int i=0;i<nodes.size();i++)if((baseline.eligibility()&(1<<i))!=0 && nodes.get(i).threshold>before)after=Math.min(after,nodes.get(i).threshold);
            box.chainPosition=after;
        }
        box.worldPosition=position(box.chainPosition);
        box.yaw=track.looping()?box.chainPosition+(track.reversed()?180:0):track.yaw();
    }
    public ChainConveyorBlockEntity conveyor(){return conveyor;}public BlockPos connection(){return connection;}
    public PackageChainTrack track(){return track;}public List<Node> nodes(){return nodes;}
}
