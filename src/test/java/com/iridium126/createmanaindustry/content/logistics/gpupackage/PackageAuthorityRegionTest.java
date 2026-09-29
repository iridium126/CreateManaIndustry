package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PackageAuthorityRegionTest {
    private static final UUID OWNER=new UUID(7,9);
    private static final PackageRegion REGION=new PackageRegion(0,0,0);
    private static PackageLease.Pose pose(double x){return new PackageLease.Pose(x,5,5,0,0,0,0);}
    private static final class Target implements PackageAuthorityRegion.Target {
        final PackageLease.Identity id;
        PackageAuthorityRegion.Snapshot current;
        boolean eligible=true,fail;
        int reads,writes,releases;
        final List<String> contents=List.of("iron:64","address:A");
        Target(long id,double x,int flags){this.id=new PackageLease.Identity(id,1);current=new PackageAuthorityRegion.Snapshot(pose(x),flags);}
        @Override public PackageLease.Identity identity(){return id;}
        @Override public PackageAuthorityRegion.Snapshot snapshot(){reads++;return current;}
        @Override public boolean eligible(){return eligible;}
        @Override public void apply(PackageAuthorityRegion.Snapshot value){current=value;writes++;if(fail){fail=false;throw new IllegalStateException("adapter failure");}}
        @Override public void released(PackageAuthorityRegion.Baseline baseline){releases++;}
    }
    private static PackageAuthorityRegion region(long tick){return new PackageAuthorityRegion(REGION,OWNER,10,1,tick);}
    private static PackageAuthorityRegion.Baseline acquire(PackageAuthorityRegion r,Target t,long tick) {
        var initial=r.offer(t,tick);assertNotNull(initial);
        var last=r.prepared(OWNER,r.epoch(),initial.index(),t.id,initial.leaseEpoch(),initial.revision(),tick);assertNotNull(last);
        assertTrue(r.finalReady(OWNER,r.epoch(),last.index(),t.id,last.leaseEpoch(),last.revision(),tick));return last;
    }
    private static PackageDeltaCodec.Entry change(int index,int mask,PackageLease.Pose pose,int flags) {
        return new PackageDeltaCodec.Entry(index,mask,PackageDeltaCodec.quantize(pose,0,0,0,flags));
    }
    @Test void movingAcquisitionCapturesFinalCheckpointInsteadOfChasingOldAck() {
        var r=region(0);var t=new Target(1,5,PackageAuthorityRegion.GROUNDED);
        var initial=r.offer(t,0);assertFalse(r.paused(t.id,0));
        t.current=new PackageAuthorityRegion.Snapshot(pose(6),PackageAuthorityRegion.GROUNDED);
        var finalBaseline=r.prepared(OWNER,10,initial.index(),t.id,initial.leaseEpoch(),initial.revision(),1);
        assertEquals(pose(6),finalBaseline.snapshot().pose());assertTrue(r.paused(t.id,1));
        assertFalse(r.finalReady(OWNER,10,initial.index(),t.id,initial.leaseEpoch(),initial.revision(),1));
        assertTrue(r.finalReady(OWNER,10,initial.index(),t.id,finalBaseline.leaseEpoch(),finalBaseline.revision(),2));
        assertEquals(List.of("iron:64","address:A"),t.contents);
    }
    @Test void sharedHeartbeatCannotExtendFinalBaselineDeadline() {
        var r=region(0);var t=new Target(1,5,PackageAuthorityRegion.GROUNDED);var offered=r.offer(t,0);
        var last=r.prepared(OWNER,10,offered.index(),t.id,offered.leaseEpoch(),offered.revision(),1);
        for(int tick=1;tick<=4;tick++)assertTrue(r.heartbeat(OWNER,10,tick));
        r.tick(4);assertFalse(r.paused(t.id,4));assertEquals(1,t.releases);
        assertFalse(r.finalReady(OWNER,10,last.index(),t.id,last.leaseEpoch(),last.revision(),4));
    }
    @Test void cancelBeforeFreezeNeverRewindsCreatePosition() {
        var r=region(0);var t=new Target(1,5,0);var offer=r.offer(t,0);
        t.current=new PackageAuthorityRegion.Snapshot(pose(9),0);
        assertTrue(r.release(OWNER,10,offer.index(),t.id,offer.leaseEpoch(),1));
        assertEquals(pose(9),t.current.pose());assertEquals(0,t.writes);
    }
    @Test void unchangedSleepingPackagesNeedNoIterationOrPositionWritesForHeartbeat() {
        var r=region(0);List<Target> targets=new ArrayList<>();
        for(int i=0;i<4096;i++){var t=new Target(i+1,5,PackageAuthorityRegion.SLEEPING);acquire(r,t,0);targets.add(t);}
        int reads=targets.stream().mapToInt(t->t.reads).sum();
        for(int tick=1;tick<=100;tick++){assertTrue(r.heartbeat(OWNER,10,tick));r.tick(tick);}
        assertEquals(reads,targets.stream().mapToInt(t->t.reads).sum());
        assertEquals(0,targets.stream().mapToInt(t->t.writes).sum());assertEquals(4096,r.size());
    }
    @Test void movingPackageCannotHideStalledGpuReadbackBehindHeartbeat() {
        var r=region(0);var t=new Target(1,5,0);acquire(r,t,0);
        for(int tick=1;tick<=3;tick++)assertTrue(r.heartbeat(OWNER,10,tick));
        assertFalse(r.paused(t.id,3));assertEquals(1,t.releases);
    }
    @Test void mixedReleaseAndPoseDeltaAreCommittedOnceAndKeepOtherPackagesLeased() {
        var r=region(0);var a=new Target(1,5,1);var b=new Target(2,5,1);var aa=acquire(r,a,0);var bb=acquire(r,b,0);
        a.eligible=false; // Unknown collision/machine context is a valid reason to hand back.
        var changes=List.of(change(aa.index(),PackageDeltaCodec.RELEASE,pose(63),0),change(bb.index(),1,pose(6),1));
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.delta(OWNER,10,1,0,1,changes,4));
        assertEquals(1,a.releases);assertFalse(r.paused(a.id,1));assertTrue(r.paused(b.id,1));assertEquals(pose(5),a.current.pose());assertEquals(pose(6),b.current.pose());
        assertEquals(PackageAuthorityRegion.Result.STALE,r.delta(OWNER,10,1,0,1,changes,4));assertEquals(1,a.releases);
    }
    @Test void pickupRaceIgnoresRetiredBaselineIndexButNeverAnUnissuedIndex() {
        var r=region(0);var a=new Target(1,5,1);var b=new Target(2,5,1);var aa=acquire(r,a,0);var bb=acquire(r,b,0);
        r.release(a.id);var changes=List.of(change(aa.index(),1,pose(6),1),change(bb.index(),1,pose(7),1));
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.delta(OWNER,10,1,0,1,changes,4));
        assertEquals(0,a.writes);assertEquals(1,a.releases);assertEquals(pose(7),b.current.pose());
        assertEquals(PackageAuthorityRegion.Result.INVALID,r.delta(OWNER,10,1,1,1,List.of(change(2,PackageDeltaCodec.RELEASE,pose(5),0)),4));
    }
    @Test void invalidMixedReleaseBatchHasNoPartialHandoff() {
        var r=region(0);var a=new Target(1,5,1);var b=new Target(2,5,1);var aa=acquire(r,a,0);var bb=acquire(r,b,0);
        assertEquals(PackageAuthorityRegion.Result.INVALID,r.delta(OWNER,10,1,0,1,
                List.of(change(aa.index(),PackageDeltaCodec.RELEASE,pose(5),0),change(bb.index(),1,pose(65),1)),4));
        assertEquals(0,a.releases);assertTrue(r.paused(a.id,1));assertEquals(0,b.writes);
    }
    @Test void movingVelocityCannotUseSleepingFlagToHideStalledState() {
        var r=region(0);var t=new Target(1,5,PackageAuthorityRegion.SLEEPING);
        t.current=new PackageAuthorityRegion.Snapshot(new PackageLease.Pose(5,5,5,1,0,0,0),PackageAuthorityRegion.SLEEPING);
        acquire(r,t,0);
        for(int tick=1;tick<=3;tick++)assertTrue(r.heartbeat(OWNER,10,tick));
        assertFalse(r.paused(t.id,3));assertEquals(1,t.releases);
    }
    @Test void allRecordsValidateBeforeAnyPoseIsApplied() {
        var r=region(0);var a=new Target(1,5,1);var b=new Target(2,5,1);
        var aa=acquire(r,a,0);var bb=acquire(r,b,0);
        var invalid=List.of(change(aa.index(),1,pose(6),1),change(bb.index(),1,pose(65),1));
        assertEquals(PackageAuthorityRegion.Result.INVALID,r.delta(OWNER,10,1,0,1,invalid,4));
        assertEquals(pose(5),a.current.pose());assertEquals(0,a.writes);assertEquals(-1,r.lastSequence());
        var valid=List.of(change(aa.index(),1,pose(6),1),change(bb.index(),1,pose(7),1));
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.delta(OWNER,10,1,0,1,valid,4));
        assertEquals(pose(6),a.current.pose());assertEquals(pose(7),b.current.pose());
        assertEquals(PackageAuthorityRegion.Result.STALE,r.delta(OWNER,10,1,0,1,valid,4));
        assertEquals(List.of("iron:64","address:A"),a.contents);
    }
    @Test void wrongOwnerEpochIdentityAndRevisionCannotChangeState() {
        var r=region(0);var t=new Target(1,5,1);var baseline=acquire(r,t,0);
        var changes=List.of(change(baseline.index(),1,pose(6),1));
        assertEquals(PackageAuthorityRegion.Result.STALE,r.delta(UUID.randomUUID(),10,1,0,1,changes,4));
        assertEquals(PackageAuthorityRegion.Result.STALE,r.delta(OWNER,9,1,0,1,changes,4));
        assertEquals(PackageAuthorityRegion.Result.STALE,r.delta(OWNER,10,2,0,1,changes,4));
        assertFalse(r.release(OWNER,10,baseline.index(),new PackageLease.Identity(1,2),baseline.leaseEpoch(),1));
        assertFalse(r.release(OWNER,10,baseline.index(),t.id,baseline.leaseEpoch()+1,1));assertEquals(0,t.writes);
    }
    @Test void failedAdapterRollsBackAndInvalidatesEveryAffectedLease() {
        var r=region(0);var a=new Target(1,5,1);var b=new Target(2,5,1);
        var aa=acquire(r,a,0);var bb=acquire(r,b,0);b.fail=true;
        var changes=List.of(change(aa.index(),1,pose(6),1),change(bb.index(),1,pose(7),1));
        assertEquals(PackageAuthorityRegion.Result.FAILED,r.delta(OWNER,10,1,0,1,changes,4));
        assertEquals(pose(5),a.current.pose());assertEquals(pose(5),b.current.pose());
        assertEquals(0,r.size());assertEquals(-1,r.lastSequence());assertEquals(1,a.releases);assertEquals(1,b.releases);
    }
    @Test void velocityYawAndFlagsSyncIndependentlyOfPosition() {
        var r=region(0);var t=new Target(1,5,1);
        t.current=new PackageAuthorityRegion.Snapshot(new PackageLease.Pose(5,5,5,0,0,0,-90),1);
        var b=acquire(r,t,0);var next=new PackageLease.Pose(5,5,5,1,2,3,-89);
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.delta(OWNER,10,1,0,1,
                List.of(change(b.index(),PackageDeltaCodec.VELOCITY|PackageDeltaCodec.YAW|PackageDeltaCodec.FLAGS,next,0)),4));
        assertEquals(5,t.current.pose().x());assertEquals(1,t.current.pose().vx());
        assertEquals(-89,t.current.pose().yaw(),.003);assertEquals(0,t.current.flags());
    }
    @Test void idsAreNotReusedWhenAnotherLifecycleTakesTheSameRegion() {
        var r=region(0);var a=new Target(1,5,1);var old=acquire(r,a,0);r.release(a.id);
        var b=new Target(2,5,1);var now=acquire(r,b,1);assertTrue(now.index()>old.index());
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.delta(OWNER,10,1,0,1,
                List.of(change(old.index(),1,pose(6),1)),4));assertEquals(0,b.writes);
    }
    @Test void repeatedFramesCannotMultiplyDisplacementBudgetInsideOneServerTick() {
        var r=region(0);var a=new Target(1,5,1);var base=acquire(r,a,0);
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.delta(OWNER,10,1,0,1,List.of(change(base.index(),1,pose(8),1)),4));
        assertEquals(PackageAuthorityRegion.Result.INVALID,r.delta(OWNER,10,1,1,1,List.of(change(base.index(),1,pose(10),1)),4));
        assertEquals(8,a.current.pose().x());
    }
    @Test void noAuthorityAndRegionCloseReturnToCreateAndRejectLateFrames() {
        var r=region(0);var t=new Target(1,5,1);var base=acquire(r,t,0);r.close();
        assertFalse(r.paused(t.id,1));assertEquals(1,t.releases);
        assertEquals(PackageAuthorityRegion.Result.STALE,r.delta(OWNER,10,1,0,1,List.of(change(base.index(),1,pose(6),1)),4));
        assertEquals(new PackageRegion(-1,33_554_432,0),PackageRegion.at(new PackageLease.Pose(-.001,2147483648.0,0,0,0,0,0)));
    }
}
