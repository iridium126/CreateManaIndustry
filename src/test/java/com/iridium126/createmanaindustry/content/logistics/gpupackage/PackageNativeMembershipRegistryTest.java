package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundPackageObserverPacket;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundPackageObserverPacket.Visual;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class PackageNativeMembershipRegistryTest {
    private static final ResourceLocation DIM=ResourceLocation.parse("minecraft:overworld"),MODEL=ResourceLocation.parse("create:cardboard_package_10x8");
    private static final PackageRegion REGION=new PackageRegion(0,0,0),OTHER=new PackageRegion(1,0,0);
    private static PackageObserverFeed.Member<Visual> member(int index,int entity,UUID uuid) {
        return new PackageObserverFeed.Member<>(index,new PackageLease.Identity(entity+1,2),10,3,ClientboundPackageObserverPacket.NO_POSE,
                new Visual(entity,uuid,MODEL,1,.75f));
    }
    private static ClientboundPackageObserverPacket packet(PackageRegion region,long stream,long sequence,int flags,List<PackageObserverFeed.Member<Visual>> members,List<PackageDeltaCodec.Entry> releases) {
        return new ClientboundPackageObserverPacket(DIM,region,10,3,stream,sequence,flags|8,members,releases);
    }
    private static PackageDeltaCodec.Entry release(int index){return new PackageDeltaCodec.Entry(index,16,ClientboundPackageObserverPacket.NO_POSE);}
    private static final class Sink implements PackageNativeMembershipRegistry.Sink {
        final List<String> calls=new ArrayList<>();
        public void confirm(PackageRegion region,long epoch,PackageObserverFeed.Member<Visual> member){calls.add("+"+member.metadata().entityId());}
        public void retire(PackageRegion region,long epoch,PackageObserverFeed.Member<Visual> member){calls.add("-"+member.metadata().entityId());}
    }
    @Test void duplicateVisualKeysMissingSequenceAndUnknownRetirementPublishNoPrefix() {
        var registry=new PackageNativeMembershipRegistry(DIM,Set.of(REGION,OTHER),4);var sink=new Sink();var uuid=UUID.randomUUID();
        var first=packet(REGION,20,0,3,List.of(member(0,0,uuid)),List.of());
        assertEquals(PackageNativeMembershipRegistry.Result.ACCEPTED,registry.apply(first,sink));
        assertEquals(PackageNativeMembershipRegistry.Result.STALE,registry.apply(first,sink));
        assertEquals(PackageNativeMembershipRegistry.Result.RESYNC,registry.apply(packet(REGION,20,2,2,List.of(member(1,1,UUID.randomUUID())),List.of()),sink));
        assertEquals(PackageNativeMembershipRegistry.Result.RESYNC,registry.apply(packet(OTHER,21,0,3,
                List.of(member(0,2,UUID.randomUUID()),member(1,3,uuid)),List.of()),sink));
        assertEquals(PackageNativeMembershipRegistry.Result.RESYNC,registry.apply(packet(OTHER,21,0,3,List.of(member(0,0,UUID.randomUUID())),List.of()),sink));
        assertEquals(PackageNativeMembershipRegistry.Result.RESYNC,registry.apply(packet(REGION,20,1,2,List.of(),List.of(release(3))),sink));
        assertEquals(List.of("+0"),sink.calls);assertEquals(1,registry.size());assertEquals(0,registry.stream(OTHER));
    }
    @Test void exactRetirementThenReintroductionAndOldCloseCannotRemoveNewStream() {
        var registry=new PackageNativeMembershipRegistry(DIM,Set.of(REGION),4);var sink=new Sink();var uuid=UUID.randomUUID();
        registry.apply(packet(REGION,20,0,3,List.of(member(0,0,uuid)),List.of()),sink);
        assertEquals(PackageNativeMembershipRegistry.Result.ACCEPTED,registry.apply(packet(REGION,20,1,2,List.of(member(1,0,uuid)),List.of(release(0))),sink));
        assertEquals(List.of("+0","-0","+0"),sink.calls);
        assertEquals(PackageNativeMembershipRegistry.Result.RESYNC,registry.apply(packet(REGION,21,0,3,List.of(),List.of()),sink));
        assertEquals(PackageNativeMembershipRegistry.Result.RESYNC,registry.apply(packet(REGION,20,0,4,List.of(),List.of()),sink));
        assertEquals(1,registry.size());registry.apply(packet(REGION,20,2,2,List.of(),List.of(release(1))),sink);
        assertEquals(PackageNativeMembershipRegistry.Result.ACCEPTED,registry.apply(packet(REGION,20,0,4,List.of(),List.of()),sink));
        assertEquals(0,registry.stream(REGION));registry.apply(packet(REGION,21,0,3,List.of(member(0,1,UUID.randomUUID())),List.of()),sink);
        assertEquals(PackageNativeMembershipRegistry.Result.STALE,registry.apply(packet(REGION,20,0,4,List.of(),List.of()),sink));assertEquals(1,registry.size());
    }
    @Test void fullCapacityIsBudgetedAcrossRegionsAndUnsupportedNamespacesAreIgnored() {
        var registry=new PackageNativeMembershipRegistry(DIM,Set.of(REGION,OTHER),131072);var sink=new Sink();
        for(int i=0;i<131072;i+=128) {
            var members=new ArrayList<PackageObserverFeed.Member<Visual>>();for(int j=i;j<i+128;j++)members.add(member(j,j,new UUID(0,j)));
            assertEquals(PackageNativeMembershipRegistry.Result.ACCEPTED,registry.apply(packet(REGION,20,i/128,i==0?3:2,members,List.of()),sink));
        }
        assertEquals(131072,registry.size());assertEquals(131072,sink.calls.size());
        assertEquals(PackageNativeMembershipRegistry.Result.RESYNC,registry.apply(packet(OTHER,21,0,3,List.of(member(0,131072,new UUID(1,0))),List.of()),sink));
        assertEquals(PackageNativeMembershipRegistry.Result.STALE,registry.apply(packet(new PackageRegion(99,0,0),22,0,3,List.of(),List.of()),sink));
        assertEquals(131072,registry.size());
    }
    @Test void acquiredAndRetiredInOneBatchNeverClaimsRenderer() {
        var registry=new PackageNativeMembershipRegistry(DIM,Set.of(REGION),4);var sink=new Sink();
        assertEquals(PackageNativeMembershipRegistry.Result.ACCEPTED,registry.apply(packet(REGION,20,0,3,
                List.of(member(0,0,UUID.randomUUID())),List.of(release(0))),sink));assertTrue(sink.calls.isEmpty());assertEquals(0,registry.size());
    }
}
