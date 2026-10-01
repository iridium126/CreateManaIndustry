package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageChainAuthorityTest {
    private static final UUID OWNER=new UUID(7,13);
    private static final class Target implements PackageChainAuthority.Target {
        final PackageLease.Identity identity;float progress=10;int reads,freezes,releases,commits,anticipations;boolean eligible=true,valid=true,refuseFreeze,failCommit,terminal,frozenRelease;
        final List<String> contents=List.of("iron:64","address:A");
        PackageChainAuthority.Baseline lastRelease;
        Target(long id){this(id,0x100000001L);}
        Target(long id,long generation){identity=new PackageLease.Identity(id,generation);}
        public PackageLease.Identity identity(){return identity;}
        public PackageChainAuthority.State snapshot(long tick){reads++;return new PackageChainAuthority.State(progress,tick,new PackageLease.Pose(progress,5,6,0,0,0,progress));}
        public boolean eligible(){return eligible;}
        public boolean freeze(){if(refuseFreeze)return false;freezes++;return true;}
        public int eligibility(){return 3;}
        public boolean validate(PackageChainAuthority.Event event,long tick){return valid;}
        public boolean commit(PackageChainAuthority.Event event,long tick){commits++;anticipations+=Integer.bitCount(event.ahead());progress=event.after();if(failCommit)throw new IllegalStateException("native callback threw after mutation");return terminal;}
        public void released(PackageChainAuthority.Baseline baseline,boolean frozen){releases++;frozenRelease=frozen;lastRelease=baseline;}
    }
    private static PackageChainAuthority core(){return new PackageChainAuthority(OWNER,0x200000003L,0);}
    private static PackageChainAuthority.Baseline acquire(PackageChainAuthority core,Target target,int track,int candidate) {
        var offer=core.offer(target,track,0x300000005L,0);assertNotNull(offer);
        var last=core.prepared(OWNER,core.epoch(),offer.index(),target.identity,offer.leaseEpoch(),offer.revision(),candidate,0);assertNotNull(last);
        assertNotNull(core.ready(OWNER,core.epoch(),last.index(),target.identity,last.leaseEpoch(),last.revision(),0));return last;
    }
    private static ByteBuffer record(Target target,int candidate,int track,int step,int actual,int ahead) {
        var raw=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        raw.putLong(0,target.identity.id()).putLong(8,target.identity.generation()).putInt(16,candidate).putLong(20,0x300000005L).putInt(28,step)
                .putInt(32,actual).putInt(36,ahead).putInt(40,track).putInt(44,1).putFloat(48,10).putFloat(52,11).putFloat(56,20);return raw;
    }
    private static ByteBuffer encode(ByteBuffer... records) {
        var raw=ByteBuffer.allocateDirect(records.length*64).order(ByteOrder.nativeOrder());for(var record:records)raw.put(record.duplicate());raw.flip();
        var wire=ByteBuffer.allocate(PackageChainEventCodec.MAX_WIRE_BYTES);PackageChainEventCodec.encode(raw,records.length,wire,new long[records.length]);return wire;
    }
    private static PackageChainAuthority.Result events(PackageChainAuthority core,long sequence,ByteBuffer... records) {
        return core.events(OWNER,core.epoch(),sequence,0,encode(records),ByteBuffer.allocateDirect(16384));
    }
    @Test void finalHandshakeFreezesTheLatestCreateStateAndRejectsStaleIdentityAndRevision() {
        var core=core();var target=new Target(1);var offer=core.offer(target,2,9,0);assertEquals(0,target.freezes);target.progress=12;
        assertNull(core.offer(new Target(1,target.identity.generation()+1),2,9,0));
        assertNull(core.prepared(OWNER,core.epoch(),offer.index(),new PackageLease.Identity(1,2),offer.leaseEpoch(),offer.revision(),0,1));
        var last=core.prepared(OWNER,core.epoch(),offer.index(),target.identity,offer.leaseEpoch(),offer.revision(),0,1);
        assertEquals(12,last.state().progress());assertEquals(1,target.freezes);assertFalse(core.active(target.identity));
        assertNull(core.ready(OWNER,core.epoch(),last.index(),target.identity,last.leaseEpoch(),offer.revision(),1));
        var active=core.ready(OWNER,core.epoch(),last.index(),target.identity,last.leaseEpoch(),last.revision(),2);
        assertNotNull(active);assertEquals(2,active.state().tick());assertTrue(core.active(target.identity));assertEquals(List.of("iron:64","address:A"),target.contents);
    }
    @Test void sharedHeartbeatDoesNotReadThousandsOfActivePackagesOrExtendFrozenPreparation() {
        var core=core();var targets=new ArrayList<Target>();for(int i=0;i<4096;i++){var target=new Target(i+1);targets.add(target);acquire(core,target,i%17,i);}
        int reads=targets.stream().mapToInt(t->t.reads).sum();
        for(int tick=1;tick<=10;tick++){assertTrue(core.heartbeat(OWNER,core.epoch(),tick));core.tick(tick);}
        assertEquals(reads,targets.stream().mapToInt(t->t.reads).sum());assertEquals(4096,core.size());
        var pending=new Target(5000);var offered=core.offer(pending,1,9,10);var last=core.prepared(OWNER,core.epoch(),offered.index(),pending.identity,offered.leaseEpoch(),offered.revision(),4096,10);assertNotNull(last);
        for(int tick=11;tick<=13;tick++){core.heartbeat(OWNER,core.epoch(),tick);core.tick(tick);}
        assertNull(core.baseline(pending.identity));assertEquals(1,pending.releases);assertTrue(pending.frozenRelease);assertEquals(4096,core.size());
        core.tick(16);assertTrue(core.closed());assertTrue(targets.stream().allMatch(t->t.releases==1));
    }
    @Test void duplicateControlsResendTheSameBaselineWithoutFreezingOrRestartingPhysics() {
        var core=core();var target=new Target(1);var offer=core.offer(target,0,9,0);
        var last=core.prepared(OWNER,core.epoch(),offer.index(),target.identity,offer.leaseEpoch(),offer.revision(),0,0);
        assertEquals(last,core.prepared(OWNER,core.epoch(),offer.index(),target.identity,offer.leaseEpoch(),offer.revision(),0,1));assertEquals(1,target.freezes);
        var active=core.ready(OWNER,core.epoch(),last.index(),target.identity,last.leaseEpoch(),last.revision(),1);
        assertEquals(active,core.ready(OWNER,core.epoch(),last.index(),target.identity,last.leaseEpoch(),last.revision(),2));
        assertEquals(active,core.prepared(OWNER,core.epoch(),offer.index(),target.identity,offer.leaseEpoch(),offer.revision(),0,2));
        assertNull(core.ready(OWNER,core.epoch(),last.index(),target.identity,last.leaseEpoch(),last.revision()-1,2));
        assertTrue(core.active(target.identity));assertEquals(0,target.releases);assertEquals(1,target.freezes);
    }
    @Test void entireBatchValidatesBeforeAnyInventoryCallbackAndDuplicatePacketsNeverRepeatIt() {
        var core=core();var a=new Target(1);var b=new Target(2);acquire(core,a,0,0);acquire(core,b,0,1);b.valid=false;
        assertEquals(PackageChainAuthority.Result.INVALID,events(core,0,record(a,0,0,1,1,0),record(b,1,0,1,1,0)));assertEquals(0,a.commits);
        b.valid=true;assertEquals(PackageChainAuthority.Result.ACCEPTED,events(core,0,record(a,0,0,1,1,0),record(b,1,0,1,1,0)));
        assertEquals(PackageChainAuthority.Result.DUPLICATE,events(core,0,record(a,0,0,1,1,0),record(b,1,0,1,1,0)));assertEquals(1,a.commits);assertEquals(1,b.commits);
        assertEquals(PackageChainAuthority.Result.INVALID,events(core,0,record(a,0,0,1,1,0)));
        assertEquals(PackageChainAuthority.Result.INVALID,events(core,2,record(a,0,0,2,1,0)));assertEquals(1,a.commits);
    }
    @Test void staleStepGenerationTrackAndIneligibleMasksCannotCommit() {
        var core=core();var target=new Target(1);acquire(core,target,3,0);
        for(int offset:new int[]{8,20,40,32}) {
            var bad=record(target,0,3,1,1,0);if(offset==8 || offset==20)bad.putLong(offset,bad.getLong(offset)+1);else bad.putInt(offset,offset==32?4:2);
            assertEquals(PackageChainAuthority.Result.INVALID,events(core,0,bad));assertEquals(0,target.commits);
        }
        assertEquals(PackageChainAuthority.Result.ACCEPTED,events(core,0,record(target,0,3,0xfffffffe,1,0)));
        assertEquals(PackageChainAuthority.Result.INVALID,events(core,1,record(target,0,3,1,1,0)));assertEquals(1,target.commits);
        assertEquals(PackageChainAuthority.Result.ACCEPTED,events(core,1,record(target,0,3,0xffffffff,1,0)));assertEquals(2,target.commits);
    }
    @Test void terminalCandidatesAcceptOnlyTheirExactLateIdentityAndAreNeverRecycled() {
        var core=core();var target=new Target(1);target.terminal=true;acquire(core,target,0,0);
        assertEquals(PackageChainAuthority.Result.ACCEPTED,events(core,0,record(target,0,0,1,1,0)));assertEquals(0,core.size());assertEquals(1,target.releases);
        assertEquals(11,target.lastRelease.state().progress());
        assertEquals(PackageChainAuthority.Result.ACCEPTED,events(core,1,record(target,0,0,2,1,0)));assertEquals(1,target.commits);
        var bad=record(new Target(2),0,0,3,1,0);assertEquals(PackageChainAuthority.Result.INVALID,events(core,2,bad));
        var replacement=new Target(3);var offer=core.offer(replacement,0,0x300000005L,0);
        assertNull(core.prepared(OWNER,core.epoch(),offer.index(),replacement.identity,offer.leaseEpoch(),offer.revision(),0,0));
        assertNull(core.offer(new Target(1),0,0x300000005L,0));
        assertNotNull(core.offer(new Target(1,target.identity.generation()+1),0,0x300000005L,0));
    }
    @Test void failedNativeCallbackInvalidatesTheEpochInsteadOfReplayingPartialSideEffects() {
        var core=core();var a=new Target(1);var b=new Target(2);acquire(core,a,0,0);acquire(core,b,1,1);a.failCommit=true;
        assertEquals(PackageChainAuthority.Result.FAILED,events(core,0,record(a,0,0,1,1,0),record(b,1,1,1,1,0)));
        assertTrue(core.closed());assertEquals(1,a.commits);assertEquals(0,b.commits);assertEquals(1,a.releases);assertEquals(1,b.releases);
        assertEquals(PackageChainAuthority.Result.STALE,events(core,0,record(a,0,0,1,1,0)));assertEquals(1,a.commits);
    }
    @Test void failedNativeFreezeDoesNotClaimThatCreateWasStopped() {
        var core=core();var target=new Target(1);target.refuseFreeze=true;var offer=core.offer(target,0,9,0);
        assertNull(core.prepared(OWNER,core.epoch(),offer.index(),target.identity,offer.leaseEpoch(),offer.revision(),0,0));
        assertEquals(1,target.releases);assertFalse(target.frozenRelease);assertEquals(0,core.size());
    }
    @Test void delayedOldAnticipationDoesNotNotifyAgainWhenAnActualEventIsAdded() {
        var core=core();var target=new Target(1);acquire(core,target,0,0);
        assertEquals(PackageChainAuthority.Result.ACCEPTED,events(core,0,record(target,0,0,1,0,1)));
        assertEquals(PackageChainAuthority.Result.ACCEPTED,events(core,1,record(target,0,0,2,2,1)));
        assertEquals(2,target.commits);assertEquals(1,target.anticipations);
    }
    @Test void trackInvalidationRestoresOnlyItsMembersAndFallbackIsNotAnInventoryEvent() {
        var core=core();var a=new Target(1);var b=new Target(2);acquire(core,a,0,0);acquire(core,b,1,1);
        core.invalidateTrack(0);assertEquals(1,a.releases);assertEquals(0,b.releases);assertEquals(1,core.size());
        var fallback=record(b,1,1,1,0,0);fallback.putInt(44,PackageChainEventCodec.FALLBACK).putLong(20,0);
        assertEquals(PackageChainAuthority.Result.ACCEPTED,events(core,0,fallback));assertEquals(0,b.commits);assertEquals(1,b.releases);
    }
}
