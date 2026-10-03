package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.*;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageRegion;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageLease;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundPackagePacket;

/** Bounded lifecycle transitions and cumulative environment ACKs. Admission overload refuses
 * just the new record; an ACK can be retried from the durable GPU journal. */
public final class PackageTransitionInbox {
    public static final int CAPACITY=1024;
    private record Key(PackageRegion region,long epoch,long revision,PackageLease.Identity identity,long lease,long baselineRevision,int index) {}
    private final ArrayDeque<ClientboundPackagePacket> controls=new ArrayDeque<>();
    private final LinkedHashMap<Key,ClientboundPackagePacket> acknowledgements=new LinkedHashMap<>();
    private boolean acknowledgementTurn;
    public boolean offer(ClientboundPackagePacket packet){
        if(packet.action()==ClientboundPackagePacket.ENVIRONMENT_ACK){
            var baseline=packet.baseline();if(baseline==null)return true;
            var key=new Key(packet.region(),packet.epoch(),packet.regionRevision(),baseline.identity(),baseline.leaseEpoch(),baseline.revision(),baseline.index());
            var previous=acknowledgements.get(key);
            if(previous!=null){if(packet.sequence()>previous.sequence())acknowledgements.put(key,packet);return true;}
            if(acknowledgements.size()<CAPACITY)acknowledgements.put(key,packet);
            return true;
        }
        int limit=packet.action()==ClientboundPackagePacket.OFFER?CAPACITY/2:CAPACITY;
        if(controls.size()>=limit)return false;
        controls.addLast(packet);return true;
    }
    public boolean isEmpty(){return controls.isEmpty()&&acknowledgements.isEmpty();}
    public int size(){return controls.size()+acknowledgements.size();}
    public ClientboundPackagePacket removeFirst(){
        acknowledgementTurn=!acknowledgementTurn;
        if(!acknowledgements.isEmpty()&&(acknowledgementTurn||controls.isEmpty())){
            var iterator=acknowledgements.entrySet().iterator();var packet=iterator.next().getValue();iterator.remove();return packet;
        }
        return controls.removeFirst();
    }
    /** Requeue the just-popped transition without applying new-admission throttling. */
    public void addLast(ClientboundPackagePacket packet){
        if(controls.size()>=CAPACITY)throw new IllegalStateException("Transition requeue without removal");controls.addLast(packet);
    }
    public void clear(){controls.clear();acknowledgements.clear();}
}
