package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

class PackageProtocolTest {
    private static PackageLease.Pose pose(double x) { return new PackageLease.Pose(x,0,0,0,0,0,0); }
    @Test void takeoverTimeoutAndOldEpochCannotMutateCheckpoint() {
        var lease=new PackageLease(new PackageLease.Identity(1,1),pose(0));
        UUID owner=UUID.randomUUID();long epoch=lease.acquire(owner,pose(1),10);
        long initialBaseline=lease.baselineRevision();
        assertFalse(lease.ready(UUID.randomUUID(),epoch,initialBaseline,10,pose(1)));
        assertFalse(lease.ready(owner,epoch,initialBaseline,11,pose(2)));
        long currentBaseline=lease.refreshAcquisition(pose(2));
        assertFalse(lease.ready(owner,epoch,initialBaseline,11,pose(2)));
        assertFalse(lease.ready(owner,epoch,currentBaseline,11,pose(2)));
        long frozenBaseline=lease.freezeBaseline(owner,epoch,currentBaseline,11,pose(2));
        assertTrue(lease.ready(owner,epoch,frozenBaseline,11,pose(2)));
        assertTrue(lease.commit(owner,epoch,0,12,pose(3),2));
        assertFalse(lease.commit(owner,epoch,0,12,pose(4),2));
        assertFalse(lease.commit(owner,epoch,1,15,pose(4),2));
        assertEquals(pose(3),lease.release());lease.finishRelease();
        assertFalse(lease.commit(owner,epoch,2,15,pose(4),2));
        long next=lease.acquire(owner,pose(3),16);assertTrue(next>epoch);
        long nextFrozen=lease.freezeBaseline(owner,next,lease.baselineRevision(),16,pose(3));
        assertTrue(lease.ready(owner,next,nextFrozen,16,pose(3)));
        assertFalse(lease.commit(owner,epoch,3,16,pose(4),2));
    }
    @Test void sleepingHeartbeatAndTransactionsAreIndependentOfPositionChanges() {
        var lease=new PackageLease(new PackageLease.Identity(1,1),pose(0));UUID owner=UUID.randomUUID();
        long epoch=lease.acquire(owner,pose(0),0);
        long frozen=lease.freezeBaseline(owner,epoch,lease.baselineRevision(),0,pose(0));
        assertTrue(lease.ready(owner,epoch,frozen,0,pose(0)));
        for(int tick=1;tick<100;tick++)assertTrue(lease.heartbeat(owner,epoch,tick));
        assertTrue(lease.claimTransaction(owner,epoch,0,99));
        assertFalse(lease.claimTransaction(owner,epoch,0,99));
        assertFalse(lease.claimTransaction(owner,epoch,2,99));
        assertTrue(lease.claimTransaction(owner,epoch,1,99));
        assertFalse(lease.commit(owner,epoch,0,99,pose(100),2));
        assertEquals(pose(0),lease.committed());
    }
    @Test void deltaRoundTripIncludesVelocityOnlyAndIntegerBoundaries() {
        var a=new PackageDeltaCodec.Quantized(Integer.MIN_VALUE,Integer.MAX_VALUE,-1,
                Short.MIN_VALUE,Short.MAX_VALUE,(short)0,(short)-1,-1);
        var b=new PackageDeltaCodec.Quantized(0,0,0,(short)3,(short)4,(short)5,(short)0,0);
        var entries=List.of(new PackageDeltaCodec.Entry(0,15,a),
                new PackageDeltaCodec.Entry(Integer.MAX_VALUE,PackageDeltaCodec.VELOCITY,b));
        ByteBuffer bytes=ByteBuffer.allocate(256);PackageDeltaCodec.encode(bytes,entries);bytes.flip();
        assertEquals(entries,PackageDeltaCodec.decode(bytes));assertFalse(bytes.hasRemaining());
        assertEquals(0,PackageDeltaCodec.changes(a,a));
        var merged=PackageDeltaCodec.merge(a,entries.get(1));
        assertEquals(a.x(),merged.x());assertEquals(3,merged.vx());
        assertEquals(PackageDeltaCodec.VELOCITY,PackageDeltaCodec.changes(a,merged));
    }
    @Test void malformedPacketsAndUnrepresentableStateAreRejected() {
        assertThrows(IllegalArgumentException.class,()->PackageDeltaCodec.decode(ByteBuffer.wrap(new byte[]{-1,-1,-1,-1,127})));
        assertThrows(IllegalArgumentException.class,()->PackageDeltaCodec.decode(ByteBuffer.wrap(new byte[]{1,0,0})));
        assertThrows(IllegalArgumentException.class,()->PackageDeltaCodec.quantize(pose(1e12),0,0,0,0));
        assertThrows(IllegalArgumentException.class,()->new PackageLease.Pose(Double.NaN,0,0,0,0,0,0));
    }
    @Test void releaseWireHasNoPoseAndCannotCombineWithStateBits() {
        var zero=new PackageDeltaCodec.Quantized(0,0,0,(short)0,(short)0,(short)0,(short)0,0);
        var release=new PackageDeltaCodec.Entry(0,PackageDeltaCodec.RELEASE,zero);
        var bytes=ByteBuffer.allocate(3);PackageDeltaCodec.encode(bytes,List.of(release));bytes.flip();
        assertEquals(List.of(release),PackageDeltaCodec.decode(bytes));assertFalse(bytes.hasRemaining());
        assertThrows(IllegalArgumentException.class,()->new PackageDeltaCodec.Entry(0,17,zero));
        assertThrows(IllegalArgumentException.class,()->PackageDeltaCodec.decode(ByteBuffer.wrap(new byte[]{1,0,17})));
        assertThrows(IllegalArgumentException.class,()->PackageDeltaCodec.merge(zero,release));
    }
}
