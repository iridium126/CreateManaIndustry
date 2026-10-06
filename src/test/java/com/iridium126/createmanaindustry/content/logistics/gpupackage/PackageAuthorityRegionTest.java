package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PackageAuthorityRegionTest {
    @Test void delayedJournalContactsFromTheLogSurviveNewerPoseConfirmations(){
        // Region-relative feet positions reconstructed from the supplied log. The
        // environment journal can wait longer than the sparse pose history window.
        double[][] cases={
                {38.5,19.624023-.375,12.5,38.63037109375,17.125,8.615966796875},
                {47.375977,21.250229-.375,17.5,60.53564453125,10.78662109375,19.576904296875},
                {47.313477,21.250229-.375,17.5,53.532470703125,4.375,25.6875}
        };
        for(int i=0;i<cases.length;i++){
            var c=cases[i];var r=region(0);var t=new Target(903+i,c[0],0);
            var contact=new PackageLease.Pose(c[0],c[1],c[2],0,0,0,0);
            var current=new PackageLease.Pose(c[3],c[4],c[5],0,0,0,0);
            t.current=new PackageAuthorityRegion.Snapshot(contact,0);var b=acquire(r,t,0);
            assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaStepped(OWNER,10,1,1,1,
                    List.of(change(b.index(),PackageDeltaCodec.POSITION,contact,0)),4,0,5652));
            assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaStepped(OWNER,10,1,2,42,
                    List.of(change(b.index(),PackageDeltaCodec.POSITION,current,0)),4,0,5693));
            assertTrue(r.environmentReachable(t.id,5652,42,contact),"retained journal contact was expired by a newer pose: case "+i);
            assertEquals(0,t.releases);assertEquals(current.x(),t.current.pose().x(),1.0/4096);
        }
    }
    @Test void agedJournalContactsRetainOneSweepSpatialAndLifecycleBounds(){
        var r=region(0);var t=new Target(906,5,0);var b=acquire(r,t,0);
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaStepped(OWNER,10,1,1,1,
                List.of(change(b.index(),PackageDeltaCodec.FLAGS,pose(5),0)),4,0,100));
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaStepped(OWNER,10,1,2,42,
                List.of(change(b.index(),PackageDeltaCodec.FLAGS,pose(5),1)),4,0,141));
        assertTrue(r.environmentReachable(t.id,100,42,pose(5)),"old contact at the confirmed position");
        assertFalse(r.environmentReachable(t.id,100,42,pose(50)),"journal delay must not grant a long-distance contact");
        assertFalse(r.environmentReachable(t.id,1000,42,pose(5)),"future interval remains invalid");
        r.release(t.id);assertFalse(r.environmentReachable(t.id,100,42,pose(5)),"retired lifecycle remains invalid");
    }
    @Test void quietPoseDoesNotExpireLaterEnvironmentContacts(){
        var r=region(0);var t=new Target(902,5,0);var b=acquire(r,t,0);
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaStepped(OWNER,10,1,1,1,List.of(change(b.index(),PackageDeltaCodec.FLAGS,pose(5),1)),4,0,1));
        int writes=t.writes;
        for(int tick=2;tick<=200;tick++){
            assertTrue(r.heartbeat(OWNER,10,tick));
            assertTrue(r.environmentReachable(t.id,tick,tick,pose(5)),"unchanged quantized pose at tick "+tick);
        }
        assertEquals(writes,t.writes,"contacts must not force pose writes or deltas");
        assertFalse(r.environmentReachable(t.id,1000,200,pose(5)),"future step still rejected");
        assertFalse(r.environmentReachable(t.id,200,200,pose(50)),"old sparse pose grants at most one new sweep");
        r.release(t.id);assertFalse(r.environmentReachable(t.id,200,200,pose(5)));
    }
    @Test void historicalEnvironmentContactSharesTheConfirmedStepBudget(){
        var r=new PackageAuthorityRegion(REGION,OWNER,10,1,0,()->200);var t=new Target(901,5,0);var b=acquire(r,t,0);
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaStepped(OWNER,10,1,1,1,List.of(change(b.index(),1,pose(5),0)),4,0,100));
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaStepped(OWNER,10,1,2,41,List.of(change(b.index(),1,pose(55),0)),4,0,140));
        assertTrue(r.environmentReachable(t.id,100,41,pose(5)),"valid historical contact farther than 36 blocks");
        assertTrue(r.environmentReachable(t.id,141,41,pose(5)),"environment uplink ahead of pose uplink");
        assertFalse(r.environmentReachable(t.id,140,41,pose(5)),"same-step contact cannot exceed one sweep");
        assertFalse(r.environmentReachable(t.id,1000,41,pose(5)),"future contact outside retained window");
        assertFalse(r.environmentReachable(t.id,100,41,pose(2000)),"historical contact still needs bounded reach");
        r.release(t.id);assertFalse(r.environmentReachable(t.id,100,41,pose(5)),"retired lifecycle");
    }
    @Test void sameStepConfirmationDoesNotGrantAnotherMotionBudget(){
        var r=region(0);var t=new Target(500,5,0);var b=acquire(r,t,0);
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaStepped(OWNER,10,1,1,4,List.of(change(b.index(),PackageDeltaCodec.POSITION,pose(9),0)),1,0,4));
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaStepped(OWNER,10,1,2,5,List.of(change(b.index(),PackageDeltaCodec.FLAGS,pose(9),1)),1,0,4));
        assertEquals(9,t.current.pose().x());assertEquals(1,t.current.flags());
        assertEquals(PackageAuthorityRegion.Result.INVALID,r.deltaStepped(OWNER,10,1,3,5,List.of(change(b.index(),PackageDeltaCodec.POSITION,pose(10),1)),1,0,4));
        assertEquals(PackageAuthorityRegion.Result.INVALID,r.deltaStepped(OWNER,10,1,3,5,List.of(change(b.index(),PackageDeltaCodec.FLAGS,pose(9),0)),1,0,3));
        assertEquals(PackageAuthorityRegion.Result.STALE,r.deltaStepped(OWNER,10,1,2,5,List.of(change(b.index(),PackageDeltaCodec.FLAGS,pose(9),0)),1,0,4));
    }
    @Test void highRpmSupportVelocityAndCorrespondingTravelStayWithinTheLease() {
        var r=region(0);var t=new Target(506,5,0);var b=acquire(r,t,0);
        var still=pose(5);
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaStepped(OWNER,10,1,1,1,
                List.of(change(b.index(),PackageDeltaCodec.VELOCITY,still,0)),4,0,1));
        var carried=new PackageLease.Pose(18.4,5,5,268,0,0,0);
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaStepped(OWNER,10,1,2,2,
                List.of(change(b.index(),PackageDeltaCodec.POSITION|PackageDeltaCodec.VELOCITY,carried,0)),4,0,2));
        assertEquals(18.4,t.current.pose().x(),1.0/4096);
        assertEquals(268,t.current.pose().vx(),1.0/16);
    }
    @Test void smallMovingColliderCorrectionsDoNotPauseTheWholePackageRegion() {
        var slowRegion=region(0);var slow=new Target(507,5,0);var slowBase=acquire(slowRegion,slow,0);
        var slowStart=new PackageLease.Pose(5,5,5,1.5823736f,0,0,0);
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,slowRegion.deltaStepped(OWNER,10,1,1,1,
                List.of(change(slowBase.index(),PackageDeltaCodec.VELOCITY,slowStart,0)),4,0,1));
        var slowMove=new PackageLease.Pose(9.1700849,5,5,1.5823736f,0,0,0);
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,slowRegion.deltaStepped(OWNER,10,1,2,2,
                List.of(change(slowBase.index(),PackageDeltaCodec.POSITION|PackageDeltaCodec.VELOCITY,slowMove,0)),4,0,2));
        assertEquals(1,slowRegion.simulatedCount());

        var fastRegion=region(0);var fast=new Target(508,5,0);var fastBase=acquire(fastRegion,fast,0);
        var fastStart=new PackageLease.Pose(5,5,5,62.8905609f,0,0,0);
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,fastRegion.deltaStepped(OWNER,10,1,1,1,
                List.of(change(fastBase.index(),PackageDeltaCodec.VELOCITY,fastStart,0)),4,0,1));
        var fastMove=new PackageLease.Pose(9.2275214,5,5,62.8905609f,0,0,0);
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,fastRegion.deltaStepped(OWNER,10,1,2,2,
                List.of(change(fastBase.index(),PackageDeltaCodec.POSITION|PackageDeltaCodec.VELOCITY,fastMove,0)),4,0,2));
        assertEquals(1,fastRegion.simulatedCount());
    }
    @Test void highRpmMovingStructureImpactFitsVelocityBudgetAcrossCatchupSteps() {
        var r=region(0);var t=new Target(509,5,0);var b=acquire(r,t,0);
        var impactVelocity=new PackageLease.Pose(5,5,5,125.288978f,0,0,0);
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaStepped(OWNER,10,1,1,1,
                List.of(change(b.index(),PackageDeltaCodec.VELOCITY,impactVelocity,0)),4,0,82));
        var afterImpact=new PackageLease.Pose(21.8920246,5,5,125.288978f,0,0,0);
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaStepped(OWNER,10,1,2,2,
                List.of(change(b.index(),PackageDeltaCodec.POSITION|PackageDeltaCodec.VELOCITY,afterImpact,0)),4,0,84));
        assertEquals(1,r.simulatedCount());assertEquals(21.8920246,t.current.pose().x(),1.0/4096);
    }
    @Test void movingStructureImpactMayStopWithinOneSweepStepWithoutRevokingTheRegion() {
        var r=region(0);var t=new Target(510,5,0);var b=acquire(r,t,0);
        var impactVelocity=new PackageLease.Pose(5,5,5,125.288978f,0,0,0);
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaStepped(OWNER,10,1,1,1,
                List.of(change(b.index(),PackageDeltaCodec.VELOCITY,impactVelocity,0)),4,0,45));
        var stopped=new PackageLease.Pose(9.539333,5,5,0,0,0,0);
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaStepped(OWNER,10,1,2,2,
                List.of(change(b.index(),PackageDeltaCodec.POSITION|PackageDeltaCodec.VELOCITY,stopped,0)),4,0,46));
        assertEquals(1,r.simulatedCount());assertEquals(9.539333,t.current.pose().x(),1.0/4096);
    }
    @Test void oneStepSweepAllowanceMatchesThePerAxisGpuCapAndRejectsLargerTeleport() {
        var r=region(0);var t=new Target(511,5,0);var b=acquire(r,t,0);
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaStepped(OWNER,10,1,1,1,
                List.of(change(b.index(),PackageDeltaCodec.POSITION,pose(5),0)),4,0,1));
        var valid=new PackageLease.Pose(37.5,5,5,0,0,0,0);
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaStepped(OWNER,10,1,2,2,
                List.of(change(b.index(),PackageDeltaCodec.POSITION,valid,0)),4,0,2));
        var outside=new PackageLease.Pose(71.1,5,5,0,0,0,0);
        assertEquals(PackageAuthorityRegion.Result.INVALID,r.deltaStepped(OWNER,10,1,3,3,
                List.of(change(b.index(),PackageDeltaCodec.POSITION,outside,0)),4,0,3));
        assertEquals(37.5,t.current.pose().x(),1.0/4096);
    }
    @Test void twoHundredTpsAcceptsFortyStepCatchupButStillRejectsReplayAndFutureClock(){
        var r=new PackageAuthorityRegion(REGION,OWNER,10,1,0,()->200);
        var t=new Target(801,5,0);var b=acquire(r,t,0);var other=new Target(802,10,0);acquire(r,other,0);
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaStepped(OWNER,10,1,1,1,List.of(change(b.index(),1,pose(5),0)),2,0,1));
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaStepped(OWNER,10,1,2,41,List.of(change(b.index(),1,pose(55),0)),2,0,41));
        assertEquals(55,t.current.pose().x());assertEquals(0,other.releases);
        assertEquals(PackageAuthorityRegion.Result.INVALID,r.deltaStepped(OWNER,10,1,3,41,List.of(change(b.index(),1,pose(56),0)),2,0,41));
        assertEquals(PackageAuthorityRegion.Result.INVALID,r.deltaStepped(OWNER,10,1,3,41,List.of(change(b.index(),1,pose(56),0)),2,0,10000));
        assertEquals(55,t.current.pose().x());assertFalse(r.expired(1041));assertTrue(r.expired(1042));
    }
    @Test void twoHundredTpsFinalDeadlineIsOneSecondAndCannotBeRenewedByHeartbeat(){
        var r=new PackageAuthorityRegion(REGION,OWNER,10,1,0,()->200);
        var t=new Target(803,5,0);var offer=r.offer(t,0);
        var last=r.prepared(OWNER,10,offer.index(),t.id,offer.leaseEpoch(),offer.revision(),10);
        for(int tick=11;tick<=210;tick++){assertTrue(r.heartbeat(OWNER,10,tick));r.tick(tick);}
        assertNotNull(r.baseline(t.id));assertEquals(0,t.releases);
        assertTrue(r.heartbeat(OWNER,10,211));r.tick(211);assertEquals(1,t.releases);
        assertFalse(r.finalReady(OWNER,10,last.index(),t.id,last.leaseEpoch(),last.revision(),211));
        var delayed=new Target(804,6,0);var o=r.offer(delayed,211);
        var f=r.prepared(OWNER,10,o.index(),delayed.id,o.leaseEpoch(),o.revision(),211);
        assertTrue(r.finalReady(OWNER,10,f.index(),delayed.id,f.leaseEpoch(),f.revision(),291));
    }
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
    @Test void fallingAcrossAuthorityRegionCommitsBeforeMigrationAndKeepsInventory() {
        var source=new PackageAuthorityRegion(new PackageRegion(0,1,0),OWNER,10,1,0);
        var t=new Target(501,5,0);t.current=new PackageAuthorityRegion.Snapshot(new PackageLease.Pose(5,64.25,5,0,-60,0,0),0);
        var baseline=acquire(source,t,0);var contents=t.contents;
        var after=new PackageLease.Pose(5,61.25,5,0,-60,0,0);
        var q=PackageDeltaCodec.quantize(after,0,64,0,0);
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,source.deltaStepped(OWNER,10,1,0,1,
                List.of(new PackageDeltaCodec.Entry(baseline.index(),15,q)),4,0,1));
        assertEquals(after,t.current.pose());assertSame(contents,t.contents);
        assertEquals(1,t.releases);assertNull(source.baseline(t.id));
        var destination=new PackageAuthorityRegion(REGION,OWNER,11,1,1);
        assertNotNull(acquire(destination,t,1));assertEquals(61.25,t.current.pose().y());
        assertEquals(PackageAuthorityRegion.Result.STALE,source.deltaStepped(OWNER,10,1,0,2,
                List.of(new PackageDeltaCodec.Entry(baseline.index(),15,q)),4,0,1));
        assertEquals(1,t.releases);assertEquals(61.25,t.current.pose().y());
    }
    private static PackageAuthorityRegion.Baseline acquire(PackageAuthorityRegion r,Target t,long tick) {
        var initial=r.offer(t,tick);assertNotNull(initial);
        var last=r.prepared(r.owner(),r.epoch(),initial.index(),t.id,initial.leaseEpoch(),initial.revision(),tick);assertNotNull(last);
        assertTrue(r.finalReady(r.owner(),r.epoch(),last.index(),t.id,last.leaseEpoch(),last.revision(),tick));return last;
    }
    private static PackageDeltaCodec.Entry change(int index,int mask,PackageLease.Pose pose,int flags) {
        return new PackageDeltaCodec.Entry(index,mask,PackageDeltaCodec.quantize(pose,0,0,0,flags));
    }
    @Test void steppedCatchupAllowsBoundedMotionButRejectsReplayAndFutureClock(){
        var r=region(0);var t=new Target(500,5,0);var b=acquire(r,t,0);
        var move=change(b.index(),PackageDeltaCodec.POSITION,pose(9),0);
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaStepped(OWNER,10,1,1,4,List.of(move),1,0,4));
        assertEquals(9,t.current.pose().x());
        assertEquals(PackageAuthorityRegion.Result.INVALID,r.deltaStepped(OWNER,10,1,2,4,List.of(change(b.index(),PackageDeltaCodec.POSITION,pose(10),0)),1,0,4));
        assertEquals(PackageAuthorityRegion.Result.INVALID,r.deltaStepped(OWNER,10,1,3,4,List.of(change(b.index(),PackageDeltaCodec.POSITION,pose(10),0)),1,0,1000));
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaStepped(OWNER,10,1,3,5,List.of(change(b.index(),PackageDeltaCodec.POSITION,pose(11),0)),1,0,5));
        assertEquals(PackageAuthorityRegion.Result.INVALID,r.deltaStepped(OWNER,10,1,4,5,List.of(change(b.index(),PackageDeltaCodec.POSITION,pose(12),0)),1,0,5));
        assertEquals(PackageAuthorityRegion.Result.INVALID,r.deltaStepped(OWNER,10,1,4,5,List.of(change(b.index(),PackageDeltaCodec.POSITION,pose(12),0)),1,0,1000));
        assertEquals(11,t.current.pose().x());
        assertTrue(r.expired(5+PackageLease.AUTHORITY_HEARTBEAT_TIMEOUT_TICKS+1));
    }
    @Test void closingRevokesImmediatelyAndDrainsAtMostTheExplicitBudget(){
        var r=region(0);var targets=new ArrayList<Target>();
        for(int i=0;i<1024;i++){var t=new Target(10000+i,5,0);targets.add(t);acquire(r,t,0);}
        r.beginClose();assertTrue(r.closed());
        assertFalse(r.simulated(targets.getLast().id,0));assertFalse(r.paused(targets.getFirst().id,0));
        assertEquals(0,targets.stream().mapToInt(t->t.releases).sum());
        assertEquals(64,r.drainClose(64));assertEquals(64,targets.stream().mapToInt(t->t.releases).sum());
        while(r.drainClose(64)>0){}
        assertEquals(1024,targets.stream().mapToInt(t->t.releases).sum());
        assertTrue(targets.stream().allMatch(t->t.writes==0&&t.releases==1));
    }
    @Test void visibleReadyRequiresTheExactLiveFinalLeaseAndCannotReviveRetirement() {
        var r=region(0);var t=new Target(199,5,1);var initial=r.offer(t,0);
        assertFalse(r.visibleReady(OWNER,10,initial.index(),t.id,initial.leaseEpoch(),initial.revision(),0));
        var last=r.prepared(OWNER,10,initial.index(),t.id,initial.leaseEpoch(),initial.revision(),0);
        assertFalse(r.visibleReady(OWNER,10,last.index(),t.id,last.leaseEpoch(),last.revision(),0));
        assertTrue(r.finalReady(OWNER,10,last.index(),t.id,last.leaseEpoch(),last.revision(),0));
        assertFalse(r.visibleReady(new UUID(77,99),10,last.index(),t.id,last.leaseEpoch(),last.revision(),0));
        assertFalse(r.visibleReady(OWNER,11,last.index(),t.id,last.leaseEpoch(),last.revision(),0));
        assertFalse(r.visibleReady(OWNER,10,last.index()+1,t.id,last.leaseEpoch(),last.revision(),0));
        assertFalse(r.visibleReady(OWNER,10,last.index(),new PackageLease.Identity(t.id.id(),2),last.leaseEpoch(),last.revision(),0));
        assertFalse(r.visibleReady(OWNER,10,last.index(),t.id,last.leaseEpoch()+1,last.revision(),0));
        assertFalse(r.visibleReady(OWNER,10,last.index(),t.id,last.leaseEpoch(),initial.revision(),0));
        assertTrue(r.visibleReady(OWNER,10,last.index(),t.id,last.leaseEpoch(),last.revision(),0));
        assertTrue(r.visibleReady(OWNER,10,last.index(),t.id,last.leaseEpoch(),last.revision(),1));
        r.release(t.id);
        assertFalse(r.visibleReady(OWNER,10,last.index(),t.id,last.leaseEpoch(),last.revision(),1));
        var fresh=acquire(r,t,1);
        assertFalse(r.visibleReady(OWNER,10,last.index(),t.id,last.leaseEpoch(),last.revision(),1));
        assertTrue(r.visibleReady(OWNER,10,fresh.index(),t.id,fresh.leaseEpoch(),fresh.revision(),1));
        t.eligible=false;
        assertFalse(r.visibleReady(OWNER,10,fresh.index(),t.id,fresh.leaseEpoch(),fresh.revision(),1));
        assertEquals(2,t.releases);
    }
    @Test void delayedVisibleReadyKeepsAQuietMovingBodyLeasedWithARegionHeartbeat() {
        var r=region(0);var t=new Target(200,5,0);var baseline=acquire(r,t,0);
        assertTrue(r.heartbeat(OWNER,10,2));
        assertTrue(r.heartbeat(OWNER,10,4));
        assertTrue(r.visibleReady(OWNER,10,baseline.index(),t.id,baseline.leaseEpoch(),baseline.revision(),4));
        assertEquals(0,t.releases);assertNotNull(r.baseline(t.id));
    }
    @Test void activeLeaseSurvivesBriefHeartbeatGapAndExpiresAfterGracePeriod() {
        var r=region(0);var t=new Target(203,5,0);acquire(r,t,0);
        assertTrue(r.paused(t.id,3));assertEquals(0,t.releases);
        assertTrue(r.paused(t.id,PackageLease.AUTHORITY_HEARTBEAT_TIMEOUT_TICKS));assertEquals(0,t.releases);
        assertFalse(r.paused(t.id,PackageLease.AUTHORITY_HEARTBEAT_TIMEOUT_TICKS+1));assertEquals(1,t.releases);
    }
    @Test void pairSuppressionRequiresBothFullFinalHandshakesAndTheSameAuthority() {
        var first=region(0);var same=new PackageAuthorityRegion(new PackageRegion(1,0,0),OWNER,11,1,0);
        var foreign=new PackageAuthorityRegion(REGION,new UUID(77,99),12,1,0);
        var a=new Target(101,5,1);var b=new Target(102,69,1);var c=new Target(103,6,1);
        acquire(first,a,0);var offer=same.offer(b,0);
        assertFalse(first.coSimulates(a.id,same,b.id,0));
        var finalState=same.prepared(OWNER,same.epoch(),offer.index(),b.id,offer.leaseEpoch(),offer.revision(),0);
        assertTrue(same.paused(b.id,0));assertFalse(first.coSimulates(a.id,same,b.id,0));
        assertTrue(same.finalReady(OWNER,same.epoch(),finalState.index(),b.id,finalState.leaseEpoch(),finalState.revision(),0));
        assertTrue(first.coSimulates(a.id,same,b.id,0));assertTrue(same.coSimulates(b.id,first,a.id,0));
        acquire(foreign,c,0);assertFalse(first.coSimulates(a.id,foreign,c.id,0));
        assertFalse(first.coSimulates(new PackageLease.Identity(101,2),same,b.id,0));
        same.release(b.id);assertFalse(first.coSimulates(a.id,same,b.id,0));assertEquals(1,b.releases);assertEquals(0,a.releases);
    }
    @Test void expiredOrIneligibleContactIsRestoredBeforeNativeResponse() {
        var first=region(0);var other=new PackageAuthorityRegion(REGION,OWNER,11,1,0);
        var a=new Target(201,5,0);a.current=new PackageAuthorityRegion.Snapshot(new PackageLease.Pose(5,5,5,1,0,0,0),0);
        var b=new Target(202,6,1);acquire(first,a,0);acquire(other,b,0);
        other.heartbeat(OWNER,other.epoch(),PackageLease.AUTHORITY_HEARTBEAT_TIMEOUT_TICKS);
        assertFalse(first.coSimulates(a.id,other,b.id,PackageLease.AUTHORITY_HEARTBEAT_TIMEOUT_TICKS+1));assertEquals(1,a.releases);assertEquals(0,b.releases);
        b.eligible=false;assertFalse(other.coSimulates(b.id,other,b.id,PackageLease.AUTHORITY_HEARTBEAT_TIMEOUT_TICKS+1));assertEquals(1,b.releases);
        assertEquals(List.of("iron:64","address:A"),a.contents);assertEquals(List.of("iron:64","address:A"),b.contents);
    }
    @Test void predictedMotionPreservesExactPositionsIndependentFieldsAndAbsoluteObserverFeed() {
        var r=region(0);var a=new Target(1,5,1);var b=new Target(2,20,1);acquire(r,a,0);acquire(r,b,0);
        var cursor=r.observers().subscribe(39);while(r.observers().poll(cursor,64)!=null){}
        var first=new PackageDeltaCodec.Quantized(2048,0,0,(short)0,(short)0,(short)0,(short)0,0);
        var zero=new PackageDeltaCodec.Quantized(0,0,0,(short)0,(short)0,(short)0,(short)0,0);
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaPredicted(OWNER,10,1,0,1,
                List.of(new PackageDeltaCodec.Entry(0,1,first),new PackageDeltaCodec.Entry(1,1,first)),4));
        assertEquals(5.5,a.current.pose().x());assertEquals(20.5,b.current.pose().x());
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaPredicted(OWNER,10,1,1,2,
                List.of(new PackageDeltaCodec.Entry(0,1,zero),new PackageDeltaCodec.Entry(1,1,zero)),4));
        assertEquals(6,a.current.pose().x());assertEquals(21,b.current.pose().x());
        // Position fields of this velocity-only record must be ignored, and must not reset
        // the POSITION predictor before the next motion update.
        var velocity=new PackageDeltaCodec.Quantized(Integer.MAX_VALUE,Integer.MIN_VALUE,19,16,-8,0,(short)0,0);
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaPredicted(OWNER,10,1,2,2,List.of(new PackageDeltaCodec.Entry(0,2,velocity)),4));
        assertEquals(6,a.current.pose().x());assertEquals(1,a.current.pose().vx());
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaPredicted(OWNER,10,1,3,3,
                List.of(new PackageDeltaCodec.Entry(0,1,zero),new PackageDeltaCodec.Entry(1,1,zero)),4));
        assertEquals(6.5,a.current.pose().x());assertEquals(21.5,b.current.pose().x());assertEquals(-.5,a.current.pose().vy());
        assertEquals(PackageAuthorityRegion.Result.STALE,r.deltaPredicted(OWNER,10,1,3,3,List.of(new PackageDeltaCodec.Entry(0,1,zero)),4));
        var batch=r.observers().poll(cursor,64);assertNotNull(batch);
        assertEquals(26624,batch.changes().getFirst().value().x());assertEquals(88064,batch.changes().getLast().value().x());
        assertEquals(List.of("iron:64","address:A"),a.contents);
    }
    @Test void rejectedPredictedPrefixCannotAdvanceHistoryAndRetiredIndicesStayRetired() {
        var r=region(0);var a=new Target(1,5,1);var b=new Target(2,20,1);acquire(r,a,0);acquire(r,b,0);
        var half=new PackageDeltaCodec.Quantized(2048,0,0,(short)0,(short)0,(short)0,(short)0,0);
        var zero=new PackageDeltaCodec.Quantized(0,0,0,(short)0,(short)0,(short)0,(short)0,0);
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaPredicted(OWNER,10,1,0,1,
                List.of(new PackageDeltaCodec.Entry(0,1,half),new PackageDeltaCodec.Entry(1,1,half)),4));
        var overflow=new PackageDeltaCodec.Quantized(Integer.MAX_VALUE,0,0,(short)0,(short)0,(short)0,(short)0,0);
        assertEquals(PackageAuthorityRegion.Result.INVALID,r.deltaPredicted(OWNER,10,1,1,2,
                List.of(new PackageDeltaCodec.Entry(0,1,half),new PackageDeltaCodec.Entry(1,1,overflow)),4));
        assertEquals(5.5,a.current.pose().x());assertEquals(20.5,b.current.pose().x());assertEquals(0,r.lastSequence());
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaPredicted(OWNER,10,1,1,2,
                List.of(new PackageDeltaCodec.Entry(0,1,zero),new PackageDeltaCodec.Entry(1,1,zero)),4));
        assertEquals(6,a.current.pose().x());assertEquals(21,b.current.pose().x());
        r.release(a.id);
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaPredicted(OWNER,10,1,2,3,
                List.of(new PackageDeltaCodec.Entry(0,1,overflow),new PackageDeltaCodec.Entry(1,1,zero)),4));
        assertEquals(6,a.current.pose().x());assertEquals(21.5,b.current.pose().x());assertEquals(1,a.releases);
        var c=new Target(3,30,1);var next=acquire(r,c,3);assertEquals(2,next.index());
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaPredicted(OWNER,10,1,3,4,List.of(new PackageDeltaCodec.Entry(2,1,zero)),4));
        assertEquals(30,c.current.pose().x()); // new lifecycle starts with zero displacement
        var base=new PackageDeltaCodec.Quantized(Integer.MAX_VALUE,0,0,(short)0,(short)0,(short)0,(short)0,0);
        var cancel=new PackageDeltaCodec.Quantized(-10,0,0,(short)0,(short)0,(short)0,(short)0,0);
        assertEquals(Integer.MAX_VALUE,PackageDeltaCodec.mergePredictedPosition(base,new PackageDeltaCodec.Entry(0,1,cancel),10,0,0).x());
        assertThrows(ArithmeticException.class,()->PackageDeltaCodec.mergePredictedPosition(base,new PackageDeltaCodec.Entry(0,1,zero),1,0,0));
    }
    @Test void relativeCommitsUseEachConfirmedBaselineAndObserverJournalRemainsAbsolute() {
        var r=region(0);var a=new Target(1,5,1);var b=new Target(2,20,1);acquire(r,a,0);acquire(r,b,0);
        var cursor=r.observers().subscribe(19);while(r.observers().poll(cursor,64)!=null){}
        var residual=new PackageDeltaCodec.Quantized(2048,0,0,(short)0,(short)0,(short)0,(short)0,0);
        var changes=List.of(new PackageDeltaCodec.Entry(0,1,residual),new PackageDeltaCodec.Entry(1,1,residual));
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaRelative(OWNER,10,1,0,1,changes,4));
        assertEquals(5.5,a.current.pose().x());assertEquals(20.5,b.current.pose().x());
        var batch=r.observers().poll(cursor,64);assertNotNull(batch);assertEquals(2,batch.changes().size());
        assertEquals(22528,batch.changes().get(0).value().x());assertEquals(83968,batch.changes().get(1).value().x());
        assertEquals(PackageAuthorityRegion.Result.STALE,r.deltaRelative(OWNER,10,1,0,1,changes,4));
        assertEquals(5.5,a.current.pose().x());assertNull(r.observers().poll(cursor,64));
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaRelative(OWNER,10,1,1,2,changes,4));
        assertEquals(6,a.current.pose().x());assertEquals(21,b.current.pose().x());
        var zero=new PackageDeltaCodec.Quantized(0,0,0,(short)0,(short)0,(short)0,(short)0,0);
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaRelative(OWNER,10,1,2,2,List.of(new PackageDeltaCodec.Entry(0,2,zero)),4));
        assertEquals(6,a.current.pose().x());assertEquals(List.of("iron:64","address:A"),a.contents);
    }
    @Test void invalidRelativeOverflowOrPrefixNeverCommitsAndLateRetirementCannotBlockLiveMembers() {
        var r=region(0);var a=new Target(1,5,1);var b=new Target(2,20,1);acquire(r,a,0);acquire(r,b,0);
        var small=new PackageDeltaCodec.Quantized(2048,0,0,(short)0,(short)0,(short)0,(short)0,0);
        var huge=new PackageDeltaCodec.Quantized(Integer.MAX_VALUE,0,0,(short)0,(short)0,(short)0,(short)0,0);
        assertEquals(PackageAuthorityRegion.Result.INVALID,r.deltaRelative(OWNER,10,1,0,1,List.of(
                new PackageDeltaCodec.Entry(0,1,small),new PackageDeltaCodec.Entry(1,1,huge)),4));
        assertEquals(5,a.current.pose().x());assertEquals(20,b.current.pose().x());assertEquals(-1,r.lastSequence());
        r.release(a.id);
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.deltaRelative(OWNER,10,1,0,1,List.of(
                new PackageDeltaCodec.Entry(0,1,huge),new PackageDeltaCodec.Entry(1,1,small)),4));
        assertEquals(20.5,b.current.pose().x());assertEquals(1,a.releases);
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
        long deadline=1+PackageLease.FINAL_BASELINE_TIMEOUT_TICKS;
        for(int tick=1;tick<=deadline;tick++)assertTrue(r.heartbeat(OWNER,10,tick));
        r.tick(deadline);assertTrue(r.paused(t.id,deadline));assertEquals(0,t.releases);
        assertTrue(r.heartbeat(OWNER,10,deadline+1));r.tick(deadline+1);assertFalse(r.paused(t.id,deadline+1));assertEquals(1,t.releases);
        assertFalse(r.finalReady(OWNER,10,last.index(),t.id,last.leaseEpoch(),last.revision(),deadline+1));
    }
    @Test void collisionWarmupAndFinalHandoffHaveSeparateBoundedDeadlines() {
        var r=region(0);var t=new Target(1,5,PackageAuthorityRegion.GROUNDED);
        var offered=r.offer(t,0);
        t.current=new PackageAuthorityRegion.Snapshot(pose(7),PackageAuthorityRegion.GROUNDED);
        r.tick(10);
        assertFalse(r.paused(t.id,10));assertEquals(0,t.releases);
        var finalBaseline=r.prepared(OWNER,10,offered.index(),t.id,offered.leaseEpoch(),offered.revision(),10);
        assertNotNull(finalBaseline);assertEquals(pose(7),finalBaseline.snapshot().pose());
        assertTrue(r.paused(t.id,10));
        long expired=10+PackageLease.FINAL_BASELINE_TIMEOUT_TICKS+1;
        for(int tick=11;tick<=expired;tick++)assertTrue(r.heartbeat(OWNER,10,tick));
        r.tick(expired);
        assertFalse(r.paused(t.id,expired));assertEquals(1,t.releases);
        assertEquals(pose(7),t.current.pose());assertEquals(0,t.writes);
    }
    @Test void finalUploadAndReadbackAtFiveFpsDoNotRevokeExistingBodies(){
        var r=region(0);var existing=new Target(701,5,0);acquire(r,existing,0);
        var added=new Target(702,6,0);var offer=r.offer(added,4);
        var last=r.prepared(OWNER,10,offer.index(),added.id,offer.leaseEpoch(),offer.revision(),8);
        for(int tick=9;tick<=16;tick++){assertTrue(r.heartbeat(OWNER,10,tick));r.tick(tick);}
        assertTrue(r.finalReady(OWNER,10,last.index(),added.id,last.leaseEpoch(),last.revision(),16));
        assertEquals(0,existing.releases);assertEquals(0,added.releases);assertEquals(2,r.size());
    }
    @Test void unansweredWarmupIsBoundedWithoutRewindingCreate() {
        var r=region(0);var t=new Target(1,5,0);var offered=r.offer(t,0);
        t.current=new PackageAuthorityRegion.Snapshot(pose(8),0);
        r.tick(PackageLease.ACQUISITION_TIMEOUT_TICKS);
        assertEquals(0,t.releases);assertFalse(r.expired(PackageLease.ACQUISITION_TIMEOUT_TICKS));
        r.tick(PackageLease.ACQUISITION_TIMEOUT_TICKS+1);
        assertEquals(1,t.releases);assertEquals(pose(8),t.current.pose());assertEquals(0,t.writes);
        assertNull(r.prepared(OWNER,10,offered.index(),t.id,offered.leaseEpoch(),offered.revision(),
                PackageLease.ACQUISITION_TIMEOUT_TICKS+1));
    }
    @Test void pendingWarmupTimeoutDoesNotRevokeAnActiveAuthority() {
        var r=region(0);var active=new Target(1,5,PackageAuthorityRegion.SLEEPING);
        acquire(r,active,0);
        var warming=new Target(2,6,0);assertNotNull(r.offer(warming,0));
        r.tick(PackageLease.ACQUISITION_TIMEOUT_TICKS+1);
        assertEquals(0,active.releases);assertEquals(1,warming.releases);
        assertEquals(1,r.size());assertTrue(r.paused(active.id,PackageLease.ACQUISITION_TIMEOUT_TICKS+1));
    }
    @Test void cancelledFinalBaselineDoesNotShortenTheNextWarmup() {
        var r=region(0);var first=new Target(1,5,0);var offer=r.offer(first,0);
        var finalBaseline=r.prepared(OWNER,10,offer.index(),first.id,offer.leaseEpoch(),offer.revision(),1);
        assertNotNull(finalBaseline);
        assertTrue(r.release(OWNER,10,finalBaseline.index(),first.id,finalBaseline.leaseEpoch(),1));
        var second=new Target(2,6,0);assertNotNull(r.offer(second,1));
        r.tick(10);
        assertEquals(0,second.releases);assertEquals(1,r.size());
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
    @Test void quietMovingPackageStaysLeasedAndAcceptsTheNextSparseDelta() {
        var r=region(0);var t=new Target(1,5,0);acquire(r,t,0);
        for(int tick=1;tick<=3;tick++)assertTrue(r.heartbeat(OWNER,10,tick));
        assertTrue(r.paused(t.id,3));assertEquals(0,t.releases);
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.delta(OWNER,10,1,0,3,
                List.of(change(0,1,pose(5.5),0)),4));
        assertEquals(5.5,t.current.pose().x());assertTrue(r.paused(t.id,3));assertEquals(0,t.releases);
    }
    @Test void mixedReleaseAndPoseDeltaAreCommittedOnceAndKeepOtherPackagesLeased() {
        var r=region(0);var a=new Target(1,5,1);var b=new Target(2,5,1);var aa=acquire(r,a,0);var bb=acquire(r,b,0);
        a.eligible=false; // Unknown collision/machine context is a valid reason to revoke authority.
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
    @Test void sleepingFlagDoesNotChangeSparseDeltaLeaseLiveness() {
        var r=region(0);var t=new Target(1,5,PackageAuthorityRegion.SLEEPING);
        t.current=new PackageAuthorityRegion.Snapshot(new PackageLease.Pose(5,5,5,1,0,0,0),PackageAuthorityRegion.SLEEPING);
        acquire(r,t,0);
        for(int tick=1;tick<=3;tick++)assertTrue(r.heartbeat(OWNER,10,tick));
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.delta(OWNER,10,1,0,3,
                List.of(change(0,1,pose(5.5),PackageAuthorityRegion.SLEEPING)),4));
        assertTrue(r.paused(t.id,3));assertEquals(0,t.releases);
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
    @Test void observersSeeOnlyActiveCommittedStatesAndNeverTheInvalidOrRolledBackPrefix() {
        var r=region(0);var a=new Target(1,5,1);var offered=r.offer(a,0);
        assertEquals(0,r.observers().size());
        var prepared=r.prepared(OWNER,10,offered.index(),a.id,offered.leaseEpoch(),offered.revision(),0);
        assertEquals(0,r.observers().size());
        assertTrue(r.finalReady(OWNER,10,prepared.index(),a.id,prepared.leaseEpoch(),prepared.revision(),0));
        var b=new Target(2,5,1);var bb=acquire(r,b,0);
        var cursor=r.observers().subscribe(100);var replica=new PackageObserverReplica<PackageAuthorityRegion.Target>(8);
        assertEquals(PackageObserverReplica.Result.ACCEPTED,replica.apply(r.observers().poll(cursor,8)));
        int reads=a.reads+b.reads;
        assertNull(r.observers().poll(cursor,8));assertEquals(reads,a.reads+b.reads);
        var invalid=List.of(change(prepared.index(),1,pose(6),1),change(bb.index(),1,pose(65),1));
        assertEquals(PackageAuthorityRegion.Result.INVALID,r.delta(OWNER,10,1,0,1,invalid,4));
        assertNull(r.observers().poll(cursor,8));
        var valid=List.of(change(prepared.index(),1,pose(6),1),change(bb.index(),1,pose(7),1));
        assertEquals(PackageAuthorityRegion.Result.ACCEPTED,r.delta(OWNER,10,1,0,1,valid,4));
        var batch=r.observers().poll(cursor,8);assertEquals(2,batch.changes().size());
        assertEquals(PackageObserverReplica.Result.ACCEPTED,replica.apply(batch));
        assertEquals(6*4096,replica.member(prepared.index()).state().x());
        b.fail=true;
        assertEquals(PackageAuthorityRegion.Result.FAILED,r.delta(OWNER,10,1,1,2,
                List.of(change(prepared.index(),1,pose(7),1),change(bb.index(),1,pose(8),1)),4));
        batch=r.observers().poll(cursor,8);
        assertTrue(batch.changes().stream().allMatch(e->e.mask()==PackageDeltaCodec.RELEASE));
        assertEquals(PackageObserverReplica.Result.ACCEPTED,replica.apply(batch));assertEquals(0,replica.size());
        assertEquals(List.of("iron:64","address:A"),a.contents);
    }
}
