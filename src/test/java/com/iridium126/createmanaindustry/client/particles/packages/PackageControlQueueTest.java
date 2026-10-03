package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.util.*;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import org.junit.jupiter.api.Test;

class PackageControlQueueTest {
    private static final PackageControlQueue.Namespace NS=new PackageControlQueue.Namespace(new PackageRegion(-19,31,47),101,1);
    private static PackageAuthorityRegion.Baseline row(int index){return new PackageAuthorityRegion.Baseline(index,new PackageLease.Identity(0x1234567800000001L+index,17),19,2,null);}
    private static int visit(PackageControlQueue.Prepared message,PackageControlBatchCodec.Visitor visitor) {
        if(message.single()==null)return PackageControlBatchCodec.visitValidated(ByteBuffer.wrap(message.body()),visitor);
        var row=message.single();if(visitor!=null)visitor.control(message.action(),row.index(),row.identity().id(),row.identity().generation(),row.leaseEpoch(),row.revision());return 1;
    }
    @Test void fullScaleControlsBatchWithoutDroppingIdentitiesOrWaitingForMoreRows() {
        var q=new PackageControlQueue();var seen=new BitSet(131072);int[] packets={0};
        for(int first=0;first<131072;first+=256) {
            for(int i=first;i<first+256;i++)q.offer(NS,9,row(i),first);
            assertEquals(PackageControlQueue.Result.SENT,q.flush(first,message->{
                assertEquals(NS,message.namespace());packets[0]++;visit(message,(a,i,id,g,l,r)->{
                    assertEquals(9,a);assertFalse(seen.get(i));seen.set(i);assertEquals(row(i).identity().id(),id);assertEquals(17,g);assertEquals(19,l);assertEquals(2,r);
                });return true;
            }));
        }
        assertEquals(131072,seen.cardinality());assertEquals(512,packets[0]);assertEquals(131072,q.stats().sentRecords());assertEquals(0,q.stats().pending());
        q.offer(NS,9,row(131072),2_000_000);int[] one={0};q.flush(2_000_000,message->{assertNotNull(message.single());one[0]=visit(message,null);return true;});
        assertEquals(1,one[0]); // No latency to fill a sparse tail.
    }
    @Test void phaseAndNamespaceOrderSurvivesSortedRowsAndMixedTransitions() {
        var q=new PackageControlQueue();var second=new PackageControlQueue.Namespace(NS.region(),102,1);
        q.offer(NS,9,row(7),0);q.offer(NS,9,row(2),0);q.offer(NS,4,row(7),0);q.offer(NS,9,row(8),0);q.offer(second,9,row(9),0);
        var order=new ArrayList<String>();q.flush(0,message->{visit(message,
                (a,i,id,g,l,r)->order.add(message.namespace().epoch()+":"+a+":"+i));return true;});
        assertEquals(List.of("101:9:2","101:9:7","101:4:7","101:9:8","102:9:9"),order);
    }
    @Test void partialTransportRetainsImmutablePreparedBodyAndCannotMergeNewRowsIntoIt() {
        var q=new PackageControlQueue();q.offer(NS,9,row(0),0);q.offer(NS,4,row(0),1);
        PackageControlQueue.Prepared[] blocked={null};int[] attempts={0};
        assertEquals(PackageControlQueue.Result.BLOCKED,q.flush(2,message->{if(attempts[0]++==0)return true;blocked[0]=message;return false;}));
        assertEquals(1,q.stats().pending());q.offer(NS,4,row(1),3);
        var indices=new ArrayList<Integer>();int[] accepted={0};
        assertEquals(PackageControlQueue.Result.SENT,q.flush(4,message->{
            if(accepted[0]++==0)assertSame(blocked[0],message);
            visit(message,(a,i,id,g,l,r)->indices.add(i));return true;
        }));
        assertEquals(List.of(0,1),indices);assertEquals(3,q.stats().sentRecords());assertEquals(0,q.stats().pending());
    }
    @Test void capacityAndDeadlineKeepUnsentControlsUntilExplicitWorldRevocation() {
        var q=new PackageControlQueue();for(int i=0;i<PackageControlQueue.MAX_PENDING;i++)q.offer(NS,4,row(i),10);
        assertThrows(IllegalStateException.class,()->q.offer(NS,4,row(4096),10));assertEquals(4096,q.stats().pending());
        assertEquals(PackageControlQueue.Result.BLOCKED,q.flush(10+PackageControlQueue.TIMEOUT_NANOS,message->false));
        assertEquals(PackageControlQueue.Result.TIMED_OUT,q.flush(11+PackageControlQueue.TIMEOUT_NANOS,message->fail("expired control was sent")));
        assertEquals(4096,q.stats().pending());q.clear();assertEquals(0,q.stats().pending());assertEquals(0,q.stats().sentRecords());
        var fresh=new PackageControlQueue.Namespace(NS.region(),999,1);q.offer(fresh,1,row(0),200_000_000);
        q.flush(200_000_000,message->{assertEquals(fresh,message.namespace());return true;});assertEquals(1,q.stats().sentRecords());
    }
    @Test void clockRollbackAndInvalidDuplicateRowsFailBeforeSendingAControlPrefix() {
        var q=new PackageControlQueue();q.offer(NS,9,row(0),10);assertEquals(PackageControlQueue.Result.TIMED_OUT,q.flush(9,message->fail("clock rollback")));
        q.clear();q.offer(NS,9,row(0),10);q.offer(NS,9,row(0),10);
        assertThrows(IllegalArgumentException.class,()->q.flush(10,message->fail("duplicate was sent")));assertEquals(2,q.stats().pending());
    }
    @Test void revokedNamespaceDoesNotDiscardOtherRegionsOrFreshEpochControls(){
        var q=new PackageControlQueue();var fresh=new PackageControlQueue.Namespace(NS.region(),102,1);
        var other=new PackageControlQueue.Namespace(new PackageRegion(1,2,3),103,1);
        q.offer(NS,9,row(0),0);q.offer(other,9,row(1),0);q.offer(NS,4,row(2),0);q.offer(fresh,9,row(3),0);
        q.clear(NS);assertEquals(2,q.stats().pending());
        var delivered=new ArrayList<PackageControlQueue.Namespace>();
        q.flush(0,message->{delivered.add(message.namespace());return true;});assertEquals(List.of(other,fresh),delivered);
    }
}
