package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundChainInteractionPacket;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageChainUseQueueTest {
    private static final PackagePoseQueryGpu.Ray RAY=new PackagePoseQueryGpu.Ray(0,0,-2,0,0,4);
    private static PackagePoseQueryGpu.Result hit(){return new PackagePoseQueryGpu.Result(0x100000005L,0x200000007L,17,9,1,3,
            0,0,0,0,0,0,0,0,0,0,0,0,0,42,90,0,0,0,0,0,0,0,0,0);}
    private static PackagePoseQueryGpu.Completed result(Object tag,PackagePoseQueryGpu.Result value){return new PackagePoseQueryGpu.Completed(0,4,0,PackagePoseQueryGpu.Kind.PICK,tag,List.of(value));}
    @Test void fullBanksKeepTheSameQueuedOperationWithoutReplacingIt() {
        var queue=new PackageChainUseQueue(7,()->0);assertTrue(queue.enqueue(RAY,"first"));var use=queue.queued();
        assertFalse(queue.enqueue(RAY,"second"));assertSame(use,queue.queued());assertFalse(queue.sentToServer());
        assertTrue(queue.submitted(use));assertNull(queue.queued());assertFalse(queue.submitted(use));
        assertTrue(queue.completed(result(use,PackagePoseQueryGpu.Result.NONE)));assertEquals(PackageChainUseQueue.Phase.REPLAY,queue.phase());
        assertFalse(queue.completed(result(use,PackagePoseQueryGpu.Result.NONE)));queue.clear();
        assertTrue(queue.enqueue(RAY,"second"));assertTrue(queue.queued().transaction()>use.transaction());
    }
    @Test void lateFlightsDoNotChangeReusedQueueOrItsNewIdentity() {
        var queue=new PackageChainUseQueue(7,()->0);queue.enqueue(RAY,"old");var old=queue.queued();queue.submitted(old);queue.clear();
        queue.enqueue(RAY,"new");var next=queue.queued();queue.submitted(next);
        assertFalse(queue.completed(result(old,hit())));assertEquals(PackageChainUseQueue.Phase.QUERY,queue.phase());
        assertTrue(queue.completed(result(next,hit())));assertEquals(hit().id(),queue.result().id());assertEquals(hit().generation(),queue.result().generation());
    }
    @Test void processingTimeoutDoesNotNeedFenceCompletionAndLateResultIsIgnored() {
        var clock=new AtomicLong();var queue=new PackageChainUseQueue(7,clock::get);queue.enqueue(RAY,"queued");var use=queue.queued();queue.submitted(use);
        clock.set(PackageChainUseQueue.PROCESSING_NANOS+1);assertEquals(PackageChainUseQueue.Phase.TIMED_OUT,queue.phase());
        assertFalse(queue.completed(result(use,hit())));assertFalse(queue.sentToServer());
    }
    @Test void onlyFullAcknowledgedIdentityCompletesSentUseAndNetworkTimeIsSeparate() {
        var clock=new AtomicLong();var queue=new PackageChainUseQueue(7,clock::get);queue.enqueue(RAY,"input");var use=queue.queued();queue.submitted(use);queue.completed(result(use,hit()));
        var identity=new PackageLease.Identity(hit().id(),hit().generation());var request=new PackageChainInteraction(7,identity,1,2,3,9,use.transaction(),42);
        clock.set(50_000_000);queue.sent(request);clock.set(550_000_000);
        assertEquals(PackageChainUseQueue.Phase.SENT,queue.phase());
        for(var bad:List.of(new ClientboundChainInteractionPacket(8,identity,use.transaction(),PackageChainAuthority.Result.ACCEPTED),
                new ClientboundChainInteractionPacket(7,new PackageLease.Identity(identity.id(),identity.generation()+1),use.transaction(),PackageChainAuthority.Result.ACCEPTED),
                new ClientboundChainInteractionPacket(7,identity,use.transaction()+1,PackageChainAuthority.Result.ACCEPTED)))assertFalse(queue.acknowledge(bad));
        assertTrue(queue.acknowledge(new ClientboundChainInteractionPacket(7,identity,use.transaction(),PackageChainAuthority.Result.ACCEPTED)));
        assertEquals(500_000_000,queue.latestNetworkNanos());assertEquals(PackageChainUseQueue.Phase.EMPTY,queue.phase());
    }
    @Test void requestAlreadySentNeverBecomesNativeReplayAfterNetworkTimeout() {
        var clock=new AtomicLong();var queue=new PackageChainUseQueue(7,clock::get);queue.enqueue(RAY,"input");var use=queue.queued();queue.submitted(use);queue.completed(result(use,hit()));
        queue.sent(new PackageChainInteraction(7,new PackageLease.Identity(hit().id(),hit().generation()),1,2,3,9,use.transaction(),42));
        clock.set(PackageChainUseQueue.NETWORK_TIMEOUT_NANOS+1);assertEquals(PackageChainUseQueue.Phase.TIMED_OUT,queue.phase());assertTrue(queue.sentToServer());
        assertThrows(IllegalStateException.class,queue::replay);
    }
}
