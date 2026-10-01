package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageChainInteractionTest {
    private static final UUID OWNER=new UUID(1,2),ACTOR=new UUID(3,4);
    private static final class Target implements PackageChainAuthority.Target {
        final PackageLease.Identity id=new PackageLease.Identity(0x100000005L,0x200000007L);
        int releases;boolean eligible=true;PackageChainAuthority.Baseline restored;
        public PackageLease.Identity identity(){return id;}
        public PackageChainAuthority.State snapshot(long tick){return state(10,tick);}
        public boolean eligible(){return eligible;}
        public boolean freeze(){return true;}
        public int eligibility(){return 0;}
        public boolean validate(PackageChainAuthority.Event e,long tick){return true;}
        public boolean commit(PackageChainAuthority.Event e,long tick){return false;}
        public void released(PackageChainAuthority.Baseline b,boolean frozen){releases++;restored=b;}
    }
    private static PackageChainAuthority.State state(float progress,long tick){return new PackageChainAuthority.State(progress,tick,new PackageLease.Pose(progress,5,6,0,0,0,42));}
    private static PackageChainAuthority.Baseline active(PackageChainAuthority core,Target t) {
        var offer=core.offer(t,7,9,0);var last=core.prepared(OWNER,core.epoch(),offer.index(),t.id,offer.leaseEpoch(),offer.revision(),1,0);
        return core.ready(OWNER,core.epoch(),last.index(),t.id,last.leaseEpoch(),last.revision(),0);
    }
    private static PackageChainInteraction request(PackageChainAuthority core,PackageChainAuthority.Baseline b,long transaction,float progress) {
        return new PackageChainInteraction(core.epoch(),b.identity(),b.leaseEpoch(),b.revision(),b.track(),b.trackRevision(),transaction,progress);
    }
    @Test void observerPickupCommitsExactlyOneNativeIdentityAndRetriesRemainIdempotent() {
        var core=new PackageChainAuthority(OWNER,13,0);var target=new Target();var base=active(core,target);var pick=request(core,base,1,11);var contents=new AtomicInteger(64);
        assertEquals(PackageChainAuthority.Result.ACCEPTED,core.pickup(ACTOR,pick,0,b->state(11,0),s->assertEquals(64,contents.getAndSet(0))));
        assertEquals(0,core.size());assertEquals(1,target.releases);assertEquals(11,target.restored.state().progress());
        assertEquals(PackageChainAuthority.Result.DUPLICATE,core.pickup(ACTOR,pick,1,b->{fail("duplicate authorization");return null;},s->fail("duplicate inventory")));
        assertEquals(PackageChainAuthority.Result.INVALID,core.pickup(ACTOR,request(core,base,1,12),1,b->state(12,1),s->fail("altered retry")));
        assertEquals(PackageChainAuthority.Result.STALE,core.pickup(OWNER,pick,1,b->state(11,1),s->fail("another player repeated pickup")));
        assertEquals(0,contents.get());assertEquals(1,target.releases);
    }
    @Test void staleIdentityLeaseTrackRevisionAndAuthorizationDoNotMoveContents() {
        var core=new PackageChainAuthority(OWNER,13,0);var target=new Target();var b=active(core,target);long tx=1;
        var invalid=List.of(new PackageChainInteraction(14,b.identity(),b.leaseEpoch(),b.revision(),b.track(),b.trackRevision(),tx++,11),
                new PackageChainInteraction(13,new PackageLease.Identity(b.identity().id(),b.identity().generation()+1),b.leaseEpoch(),b.revision(),b.track(),b.trackRevision(),tx++,11),
                new PackageChainInteraction(13,b.identity(),b.leaseEpoch()+1,b.revision(),b.track(),b.trackRevision(),tx++,11),
                new PackageChainInteraction(13,b.identity(),b.leaseEpoch(),b.revision()+1,b.track(),b.trackRevision(),tx++,11),
                new PackageChainInteraction(13,b.identity(),b.leaseEpoch(),b.revision(),b.track()+1,b.trackRevision(),tx++,11),
                new PackageChainInteraction(13,b.identity(),b.leaseEpoch(),b.revision(),b.track(),b.trackRevision()+1,tx++,11));
        for(var r:invalid)assertEquals(PackageChainAuthority.Result.STALE,core.pickup(ACTOR,r,0,ignored->{fail("stale request reached native validation");return null;},s->fail("stale inventory")));
        var denied=request(core,b,tx++,11);assertEquals(PackageChainAuthority.Result.INVALID,core.pickup(ACTOR,denied,0,ignored->null,s->fail()));
        assertEquals(PackageChainAuthority.Result.INVALID,core.pickup(ACTOR,denied,0,ignored->state(11,0),s->fail("denied serial became accepted")));
        assertEquals(PackageChainAuthority.Result.INVALID,core.pickup(ACTOR,request(core,b,tx,11),0,ignored->state(12,0),s->fail("authorization changed progress")));
        assertTrue(core.active(target.id));assertEquals(0,target.releases);
    }
    @Test void failedCallbackInvalidatesEpochAndCannotReplaySideEffects() {
        var core=new PackageChainAuthority(OWNER,13,0);var target=new Target();var pick=request(core,active(core,target),1,11);var calls=new AtomicInteger();
        assertEquals(PackageChainAuthority.Result.FAILED,core.pickup(ACTOR,pick,0,b->state(11,0),s->{calls.incrementAndGet();throw new IllegalStateException("native callback");}));
        assertTrue(core.closed());assertEquals(1,target.releases);
        assertEquals(PackageChainAuthority.Result.STALE,core.pickup(ACTOR,pick,0,b->state(11,0),s->calls.incrementAndGet()));assertEquals(1,calls.get());
    }
    @Test void reentrantRequestsCannotConsumeAnotherItemBeforeFirstCallbackFinishes() {
        var core=new PackageChainAuthority(OWNER,13,0);var target=new Target();var pick=request(core,active(core,target),1,11);
        assertEquals(PackageChainAuthority.Result.ACCEPTED,core.pickup(ACTOR,pick,0,b->state(11,0),s->{
            assertEquals(PackageChainAuthority.Result.STALE,core.pickup(ACTOR,pick,0,b->state(11,0),again->fail("reentrant pickup")));
            core.release(target.id);
        }));assertEquals(1,target.releases);
    }
    @Test void expiredHeartbeatAndPreparedLeaseRejectInteractionWithoutCallbacks() {
        var core=new PackageChainAuthority(OWNER,13,0);var target=new Target();var offer=core.offer(target,7,9,0);
        assertEquals(PackageChainAuthority.Result.STALE,core.pickup(ACTOR,request(core,offer,1,11),0,b->{fail();return null;},s->fail()));
        var base=core.prepared(OWNER,13,offer.index(),target.id,offer.leaseEpoch(),offer.revision(),1,0);
        assertEquals(PackageChainAuthority.Result.STALE,core.pickup(ACTOR,request(core,base,2,11),0,b->{fail();return null;},s->fail()));
        core.ready(OWNER,13,base.index(),target.id,base.leaseEpoch(),base.revision(),0);
        assertEquals(PackageChainAuthority.Result.STALE,core.pickup(ACTOR,request(core,base,3,11),3,b->{fail();return null;},s->fail()));assertEquals(0,target.releases);
    }
}
