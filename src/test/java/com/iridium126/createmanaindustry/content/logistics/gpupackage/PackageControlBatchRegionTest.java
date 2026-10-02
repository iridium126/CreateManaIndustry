package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;

class PackageControlBatchRegionTest {
    private static final UUID OWNER=new UUID(3,7);
    private static final class Target implements PackageAuthorityRegion.Target {
        final PackageLease.Identity identity;
        final List<String> contents=List.of("iron:64","copper:17","address:A");
        final PackageAuthorityRegion.Snapshot state=new PackageAuthorityRegion.Snapshot(new PackageLease.Pose(5,5,5,1,0,0,0),0);
        int releases;
        Target(int index){identity=new PackageLease.Identity(0x1234567800000001L+index,1);}
        public PackageLease.Identity identity(){return identity;}
        public PackageAuthorityRegion.Snapshot snapshot(){return state;}
        public boolean eligible(){return true;}
        public void apply(PackageAuthorityRegion.Snapshot state){assertEquals(this.state,state);}
        public void released(PackageAuthorityRegion.Baseline ignored){releases++;}
    }
    private static ByteBuffer encode(int action,List<PackageAuthorityRegion.Baseline> rows){var bytes=ByteBuffer.allocate(PackageControlBatchCodec.MAX_BYTES);PackageControlBatchCodec.encode(bytes,action,rows);return bytes.flip();}
    @Test void allHandshakePhasesAndRetirementPreserveExactLeasesAndInventory() {
        var region=new PackageAuthorityRegion(new PackageRegion(0,0,0),OWNER,101,1,0);var targets=new ArrayList<Target>();
        var offers=new ArrayList<PackageAuthorityRegion.Baseline>();var finals=new ArrayList<PackageAuthorityRegion.Baseline>();
        for(int i=0;i<256;i++){var t=new Target(i);targets.add(t);offers.add(region.offer(t,0));}
        PackageControlBatchCodec.visitValidated(encode(1,offers),(a,i,id,g,l,r)->{
            assertEquals(1,a);var last=region.prepared(OWNER,101,i,new PackageLease.Identity(id,g),l,r,0);assertNotNull(last);finals.add(last);
        });
        assertEquals(256,finals.size());assertEquals(0,region.simulatedCount());
        PackageControlBatchCodec.visitValidated(encode(2,finals),(a,i,id,g,l,r)->assertTrue(region.finalReady(OWNER,101,i,new PackageLease.Identity(id,g),l,r,0)));
        assertEquals(256,region.simulatedCount());
        var stale=finals.get(17);var mixed=new ArrayList<>(finals);
        mixed.set(17,new PackageAuthorityRegion.Baseline(stale.index(),new PackageLease.Identity(stale.identity().id(),2),stale.leaseEpoch(),stale.revision(),stale.snapshot()));
        int[] visible={0};PackageControlBatchCodec.visitValidated(encode(9,mixed),(a,i,id,g,l,r)->{
            if(region.visibleReady(OWNER,101,i,new PackageLease.Identity(id,g),l,r,0))visible[0]++;
        });assertEquals(255,visible[0]);assertEquals(0,targets.get(17).releases);
        PackageControlBatchCodec.visitValidated(encode(4,finals),(a,i,id,g,l,r)->assertTrue(region.release(OWNER,101,i,new PackageLease.Identity(id,g),l,0)));
        assertEquals(0,region.simulatedCount());
        PackageControlBatchCodec.visitValidated(encode(4,finals),(a,i,id,g,l,r)->assertFalse(region.release(OWNER,101,i,new PackageLease.Identity(id,g),l,0)));
        for(var target:targets){assertEquals(1,target.releases);assertEquals(List.of("iron:64","copper:17","address:A"),target.contents);}
    }
    @Test void corruptedTailCannotFreezeTheValidPrefixAndDelayedVisibilityCannotReviveMotion() {
        var region=new PackageAuthorityRegion(new PackageRegion(0,0,0),OWNER,101,1,0);var first=new Target(1);var second=new Target(2);
        var offers=List.of(region.offer(first,0),region.offer(second,0));var bytes=encode(1,offers);bytes.limit(bytes.limit()-1);
        assertThrows(IllegalArgumentException.class,()->PackageControlBatchCodec.visitValidated(bytes,(a,i,id,g,l,r)->
                region.prepared(OWNER,101,i,new PackageLease.Identity(id,g),l,r,0)));
        assertFalse(region.paused(first.identity,0));assertFalse(region.paused(second.identity,0));
        var finals=new ArrayList<PackageAuthorityRegion.Baseline>();
        PackageControlBatchCodec.visitValidated(encode(1,offers),(a,i,id,g,l,r)->finals.add(region.prepared(OWNER,101,i,new PackageLease.Identity(id,g),l,r,0)));
        PackageControlBatchCodec.visitValidated(encode(2,finals),(a,i,id,g,l,r)->assertTrue(region.finalReady(OWNER,101,i,new PackageLease.Identity(id,g),l,r,0)));
        assertTrue(region.heartbeat(OWNER,101,2));assertTrue(region.heartbeat(OWNER,101,4));
        PackageControlBatchCodec.visitValidated(encode(9,finals),(a,i,id,g,l,r)->assertTrue(region.visibleReady(OWNER,101,i,new PackageLease.Identity(id,g),l,r,4)));
        assertEquals(0,first.releases);assertEquals(0,second.releases);
    }
}
