package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class PackageObserverReplicaTest {
    private static PackageObserverFeed.Member<String> member(int index,long id,long generation) {
        return new PackageObserverFeed.Member<>(index,new PackageLease.Identity(id,generation),10,2,
                new PackageDeltaCodec.Quantized(1,2,3,(short)4,(short)5,(short)6,(short)7,1),"box");
    }
    @Test void missingSequenceRequiresResyncAndDoesNotMutateTheLastGoodState() {
        var replica=new PackageObserverReplica<String>(8);var member=member(65536,0x1234567800000001L,0x2345678900000001L);
        var baseline=new PackageObserverFeed.Batch<>(10,0,true,true,List.of(member),List.<PackageDeltaCodec.Entry>of());
        assertEquals(PackageObserverReplica.Result.ACCEPTED,replica.apply(baseline));
        var release=new PackageDeltaCodec.Entry(65536,PackageDeltaCodec.RELEASE,member.state());
        assertEquals(PackageObserverReplica.Result.RESYNC,replica.apply(new PackageObserverFeed.Batch<>(10,2,false,true,List.of(),List.of(release))));
        assertEquals(1,replica.size());assertEquals(PackageObserverReplica.Result.STALE,replica.apply(baseline));
        assertEquals(PackageObserverReplica.Result.ACCEPTED,replica.apply(new PackageObserverFeed.Batch<>(10,1,false,true,List.of(),List.of(release))));
        assertEquals(0,replica.size());
        assertEquals(PackageObserverReplica.Result.RESYNC,replica.apply(new PackageObserverFeed.Batch<>(10,2,false,true,List.of(member(65536,99,3)),List.of())));
    }
    @Test void unknownDeltaDuplicateIntroductionAndCapacityFailAtomically() {
        var replica=new PackageObserverReplica<String>(1);var member=member(0,1,1);
        assertEquals(PackageObserverReplica.Result.ACCEPTED,replica.apply(new PackageObserverFeed.Batch<>(10,0,true,true,List.of(member),List.of())));
        assertEquals(PackageObserverReplica.Result.RESYNC,replica.apply(new PackageObserverFeed.Batch<>(10,1,false,true,List.of(),
                List.of(new PackageDeltaCodec.Entry(9,PackageDeltaCodec.POSITION,member.state())))));
        assertEquals(PackageObserverReplica.Result.RESYNC,replica.apply(new PackageObserverFeed.Batch<>(10,1,false,true,List.of(member(1,2,1)),List.of())));
        assertEquals(1,replica.size());assertEquals(member,replica.member(0));
        assertEquals(PackageObserverReplica.Result.RESYNC,replica.apply(new PackageObserverFeed.Batch<>(11,0,true,true,List.of(member,member),List.of())));
        assertEquals(10,replica.stream());assertEquals(1,replica.size());
    }
    @Test void sameStableIdentityCanBeReacquiredOnlyAfterItsOldIndexRetires() {
        var replica=new PackageObserverReplica<String>(8);var old=member(0,1,1);var next=member(1,1,1);
        assertEquals(PackageObserverReplica.Result.ACCEPTED,replica.apply(new PackageObserverFeed.Batch<>(10,0,true,true,List.of(old),List.of())));
        assertEquals(PackageObserverReplica.Result.RESYNC,replica.apply(new PackageObserverFeed.Batch<>(10,1,false,true,List.of(next),List.of())));
        assertEquals(PackageObserverReplica.Result.ACCEPTED,replica.apply(new PackageObserverFeed.Batch<>(10,1,false,true,List.of(next),
                List.of(new PackageDeltaCodec.Entry(0,PackageDeltaCodec.RELEASE,old.state())))));
        assertNull(replica.member(0));assertEquals(next,replica.member(1));
    }
    @Test void renewedBaselineAllowsCapacityReuseAndRejectsLateClose() {
        var replica=new PackageObserverReplica<String>(1);
        assertEquals(PackageObserverReplica.Result.ACCEPTED,replica.apply(new PackageObserverFeed.Batch<>(10,0,true,true,List.of(member(0,1,1)),List.of())));
        assertEquals(PackageObserverReplica.Result.ACCEPTED,replica.apply(new PackageObserverFeed.Batch<>(11,0,true,true,List.of(member(7,2,3)),List.of())));
        assertFalse(replica.close(10));assertEquals(1,replica.size());assertTrue(replica.close(11));
        assertEquals(PackageObserverReplica.Result.STALE,replica.apply(new PackageObserverFeed.Batch<>(11,1,false,true,List.of(),List.of())));
    }
}
