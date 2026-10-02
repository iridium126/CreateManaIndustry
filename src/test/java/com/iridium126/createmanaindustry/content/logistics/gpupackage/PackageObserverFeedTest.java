package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PackageObserverFeedTest {
    private static PackageObserverFeed.Member<String> member(int index,int x) {
        return new PackageObserverFeed.Member<>(index,new PackageLease.Identity(0x1234567800000000L+index+1,0x2345678900000001L),
                0x3456789000000001L,3,state(x),"box:"+index);
    }
    private static PackageDeltaCodec.Quantized state(int x){return new PackageDeltaCodec.Quantized(x,20000,-9000,(short)7,(short)-8,(short)9,(short)-10,1);}
    private static PackageDeltaCodec.Entry change(int id,int x){return new PackageDeltaCodec.Entry(id,PackageDeltaCodec.POSITION,state(x));}
    private static <M> void drain(PackageObserverFeed<M> feed,PackageObserverFeed.Cursor cursor,PackageObserverReplica<M> replica,int budget) {
        for(int i=0;i<100000;i++) {
            var batch=feed.poll(cursor,budget);
            if(batch!=null) {
                assertTrue(batch.baselines().size()+batch.changes().size()<=budget);
                assertEquals(PackageObserverReplica.Result.ACCEPTED,replica.apply(batch));
            }
            if(cursor.complete() && batch==null)return;
        }
        fail("Observer drain did not converge");
    }
    @Test void fullCapacityLateJoinIsBudgetedAndIdlePollsProduceNoPackets() {
        var feed=new PackageObserverFeed<String>();
        for(int i=0;i<131072;i++)feed.activate(member(i,i));
        var cursor=feed.subscribe(0x4567890100000001L);var replica=new PackageObserverReplica<String>(131072);
        drain(feed,cursor,replica,128);
        assertEquals(131072,replica.size());assertEquals(member(131071,131071),replica.member(131071));
        assertTrue(replica.complete());assertNull(feed.poll(cursor,128));
        assertTrue(feed.unsubscribe(cursor));assertFalse(feed.unsubscribe(cursor));
        assertThrows(IllegalArgumentException.class,()->feed.poll(cursor,128));
    }
    @Test void acquisitionsMayFinishOutOfOrderWhileSnapshotIsBeingScanned() {
        var feed=new PackageObserverFeed<String>(64);feed.activate(member(4,4));feed.activate(member(2,2));
        var cursor=feed.subscribe(10);var replica=new PackageObserverReplica<String>(32);
        assertEquals(PackageObserverReplica.Result.ACCEPTED,replica.apply(feed.poll(cursor,1))); // index 2 scanned
        feed.activate(member(1,1)); // lower pending offer becomes active after the scan passed it
        feed.accepted(List.of(change(2,20),change(4,40)));
        assertTrue(feed.retire(4,member(4,4).identity())); // never introduced to this observer
        feed.activate(member(8,8));feed.accepted(List.of(change(8,80)));
        drain(feed,cursor,replica,1);
        assertEquals(3,replica.size());assertEquals(1,replica.member(1).state().x());
        assertEquals(20,replica.member(2).state().x());assertNull(replica.member(4));assertEquals(80,replica.member(8).state().x());
    }
    @Test void changesAreSortedCoalescedAndPreserveIndependentFields() {
        var feed=new PackageObserverFeed<String>();feed.activate(member(0,0));feed.activate(member(1,1));
        var cursor=feed.subscribe(10);var replica=new PackageObserverReplica<String>(16);drain(feed,cursor,replica,16);
        feed.accepted(List.of(change(1,22)));feed.accepted(List.of(change(0,33)));
        var velocity=new PackageDeltaCodec.Quantized(0,0,0,(short)100,(short)-200,(short)300,(short)0,0);
        feed.accepted(List.of(new PackageDeltaCodec.Entry(0,PackageDeltaCodec.VELOCITY,velocity)));
        feed.accepted(List.of(new PackageDeltaCodec.Entry(0,PackageDeltaCodec.FLAGS,new PackageDeltaCodec.Quantized(0,0,0,(short)0,(short)0,(short)0,(short)0,3))));
        var batch=feed.poll(cursor,16);assertEquals(2,batch.changes().size());
        assertEquals(0,batch.changes().getFirst().id());assertEquals(11,batch.changes().getFirst().mask());
        assertEquals(PackageObserverReplica.Result.ACCEPTED,replica.apply(batch));
        assertEquals(33,replica.member(0).state().x());assertEquals(100,replica.member(0).state().vx());
        assertEquals(3,replica.member(0).state().flags());assertEquals(22,replica.member(1).state().x());
        long sequence=replica.sequence();feed.accepted(List.of(change(1,22)));
        assertNull(feed.poll(cursor,16));assertEquals(sequence,replica.sequence());
    }
    @Test void overwhelmedObserverResetsWithoutHoldingUpAuthoritativeUpdates() {
        var feed=new PackageObserverFeed<String>(4);feed.activate(member(0,0));
        var cursor=feed.subscribe(10);var replica=new PackageObserverReplica<String>(8);drain(feed,cursor,replica,4);
        for(int i=1;i<=9;i++)feed.accepted(List.of(change(0,i)));
        assertEquals(9,feed.member(0).state().x());assertThrows(PackageObserverFeed.LaggedException.class,()->feed.poll(cursor,4));
        feed.unsubscribe(cursor);var next=feed.subscribe(11);drain(feed,next,replica,4);
        assertEquals(9,replica.member(0).state().x());assertFalse(replica.close(10));assertEquals(1,replica.size());
        assertTrue(replica.close(11));assertEquals(0,replica.size());
        assertEquals(PackageObserverReplica.Result.STALE,replica.apply(new PackageObserverFeed.Batch<>(10,0,true,true,List.of(member(0,0)),List.of())));
    }
    @Test void retirementNeverReusesAnIndexOrAcceptsAnotherGeneration() {
        var feed=new PackageObserverFeed<String>();feed.activate(member(0,0));
        assertFalse(feed.retire(0,new PackageLease.Identity(member(0,0).identity().id(),1)));
        var cursor=feed.subscribe(10);var replica=new PackageObserverReplica<String>(8);drain(feed,cursor,replica,8);
        feed.accepted(List.of(change(0,7)));assertTrue(feed.retire(0,member(0,0).identity()));
        var batch=feed.poll(cursor,8);assertEquals(1,batch.changes().size());assertEquals(PackageDeltaCodec.RELEASE,batch.changes().getFirst().mask());
        assertEquals(PackageObserverReplica.Result.ACCEPTED,replica.apply(batch));assertEquals(0,replica.size());
        feed.accepted(List.of(change(0,8),change(2,9))); // server-validated retired/pending indices are irrelevant
        assertNull(feed.poll(cursor,8));assertThrows(IllegalArgumentException.class,()->feed.activate(member(0,3)));
    }
    @Test void invalidWholeBatchDoesNotPublishItsValidPrefix() {
        var feed=new PackageObserverFeed<String>();feed.activate(member(0,0));feed.activate(member(1,1));
        var cursor=feed.subscribe(10);var replica=new PackageObserverReplica<String>(8);drain(feed,cursor,replica,8);
        var invalid=new PackageDeltaCodec.Entry(1,PackageDeltaCodec.FLAGS,new PackageDeltaCodec.Quantized(0,0,0,(short)0,(short)0,(short)0,(short)0,4));
        assertThrows(IllegalArgumentException.class,()->feed.accepted(List.of(change(0,9),invalid)));
        assertEquals(0,feed.member(0).state().x());assertNull(feed.poll(cursor,8));
        assertEquals(PackageObserverReplica.Result.RESYNC,replica.apply(new PackageObserverFeed.Batch<>(10,1,false,true,List.of(),List.of(change(0,9),invalid))));
        assertEquals(0,replica.member(0).state().x());assertEquals(0,replica.sequence());
    }
    @Test void randomMutationsDuringSmallSnapshotBatchesConvergeWithoutRevertingNewBaselines() {
        var feed=new PackageObserverFeed<String>(2048);for(int i=0;i<300;i++)feed.activate(member(i,i));
        var cursor=feed.subscribe(10);var replica=new PackageObserverReplica<String>(1024);var random=new Random(918273);
        for(int round=0;round<700;round++) {
            int id=random.nextInt(300);if(feed.member(id)!=null) {
                if(round%17==0)feed.retire(id,member(id,id).identity());else feed.accepted(List.of(change(id,1000+round)));
            }
            if(round%19==0)feed.activate(member(300+round,2000+round));
            var batch=feed.poll(cursor,3);if(batch!=null)assertEquals(PackageObserverReplica.Result.ACCEPTED,replica.apply(batch));
        }
        drain(feed,cursor,replica,3);assertEquals(feed.size(),replica.size());
        for(int i=0;i<1000;i++)assertEquals(feed.member(i),replica.member(i),"index "+i);
    }
    @Test void slowReaderDoesNotPreventOtherSubscribersFromAdvancing() {
        var feed=new PackageObserverFeed<String>(4);feed.activate(member(0,0));
        var slow=feed.subscribe(10);var fast=feed.subscribe(11);var replica=new PackageObserverReplica<String>(8);drain(feed,fast,replica,4);
        for(int i=1;i<=20;i++){feed.accepted(List.of(change(0,i)));drain(feed,fast,replica,4);}
        assertEquals(20,replica.member(0).state().x());assertThrows(PackageObserverFeed.LaggedException.class,()->feed.poll(slow,4));
    }
    @Test void ongoingMotionCannotStarveSnapshotDiscoveryOrOverwriteItsNewerBaselines() {
        var feed=new PackageObserverFeed<String>(4096);for(int i=0;i<128;i++)feed.activate(member(i,i));
        var cursor=feed.subscribe(10);var replica=new PackageObserverReplica<String>(256);
        var changes=new ArrayList<PackageDeltaCodec.Entry>();
        for(int round=1;round<=64;round++) {
            changes.clear();for(int i=0;i<128;i++)changes.add(change(i,1000+round));
            feed.accepted(changes);
            for(int poll=0;poll<20;poll++) {
                var batch=feed.poll(cursor,8);
                if(batch!=null)assertEquals(PackageObserverReplica.Result.ACCEPTED,replica.apply(batch));
            }
            if(round==4){assertTrue(cursor.complete(),"Discovery should advance while updates keep arriving");assertEquals(128,replica.size());}
        }
        drain(feed,cursor,replica,8);
        for(int i=0;i<128;i++)assertEquals(feed.member(i),replica.member(i));
    }
    @Test void snapshotCoalescingAndRetirementPreserveActualConfirmationTimes() {
        var feed=new PackageObserverFeed<String>();var initial=member(0,0);
        feed.activate(new PackageObserverFeed.Member<>(initial.index(),initial.identity(),initial.leaseEpoch(),initial.revision(),initial.state(),initial.metadata(),100));
        var cursor=feed.subscribe(10);assertEquals(100,feed.poll(cursor,8).baselines().getFirst().stateTick());
        feed.accepted(List.of(change(0,5)),101);feed.accepted(List.of(change(0,6)),102);
        var batch=feed.poll(cursor,8);assertEquals(1,batch.changes().size());assertEquals(102,batch.stateTick(0));
        assertEquals(102,feed.member(0).stateTick());
        feed.accepted(List.of(change(0,6)),103);assertNull(feed.poll(cursor,8));assertEquals(103,feed.member(0).stateTick());
        assertThrows(IllegalArgumentException.class,()->feed.accepted(List.of(change(0,7)),102));
        assertEquals(6,feed.member(0).state().x());assertEquals(103,feed.member(0).stateTick());
        feed.retire(0,initial.identity());batch=feed.poll(cursor,8);assertEquals(PackageDeltaCodec.RELEASE,batch.changes().getFirst().mask());
        assertEquals(103,batch.stateTick(0));
    }
}
