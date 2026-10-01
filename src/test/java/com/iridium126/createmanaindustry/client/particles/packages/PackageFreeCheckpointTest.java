package com.iridium126.createmanaindustry.client.particles.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageLease;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageFreeCheckpointTest {
    private static final PackageLease.Identity ID=new PackageLease.Identity(0x100000003L,0x200000005L);
    private static PackagePoseQueryGpu.Result pose(long id,long generation,int flags,float state,float ground,float halfHeight,float oldX) {
        return new PackagePoseQueryGpu.Result(id,generation,7,18,flags,-1,
                3,4,5,35,10,-20,30,state,3,4,5,35,0,0,ground,halfHeight,
                oldX,3,4,30,0,0,0,0);
    }
    private static PackagePoseQueryGpu.Result pose(){return pose(ID.id(),ID.generation(),0,0,1,.375f,2);}
    private static PackageFreeUpload.Checkpoint restore(PackagePoseQueryGpu.Result p){return PackageFreeUpload.retainedCheckpoint(p,ID,.75f,-30_000_000.25,32_000_000.5,-64.25);}
    @Test void restoresDoubleOriginFeetGroundAndPerTickVelocityWithoutLosingHistory() {
        var saved=restore(pose());
        assertEquals(-29_999_997.25,saved.pose().x());assertEquals(32_000_004.125,saved.pose().y());assertEquals(-59.25,saved.pose().z());
        assertEquals(.5f,saved.pose().vx());assertEquals(-1,saved.pose().vy());assertEquals(1.5f,saved.pose().vz());assertEquals(35,saved.pose().yaw());assertTrue(saved.ground());
        assertEquals(-29_999_998.25,saved.previous().x());assertEquals(32_000_003.125,saved.previous().y());assertEquals(30,saved.previous().yaw());
        for(double partial:new double[]{0,.25,.5,.75,1})assertEquals(-30_000_000.25+2+partial,
                saved.previous().x()+(saved.pose().x()-saved.previous().x())*partial,1e-8);
        var retired=pose(ID.id(),ID.generation(),4,-3,0,.375f,2);assertFalse(restore(retired).ground());assertEquals(saved.pose(),restore(retired).pose());
    }
    @Test void rejectsForeignIdentityPreparedHiddenChainAndInvalidPhysicalState() {
        for(var p:new PackagePoseQueryGpu.Result[]{PackagePoseQueryGpu.Result.NONE,
                pose(ID.id()+1,ID.generation(),0,0,1,.375f,2),pose(ID.id(),ID.generation()+1,0,0,1,.375f,2),
                pose(ID.id(),ID.generation(),1,0,1,.375f,2),pose(ID.id(),ID.generation(),4,0,1,.375f,2),
                pose(ID.id(),ID.generation(),0,-2,1,.375f,2),pose(ID.id(),ID.generation(),0,Float.NaN,1,.375f,2),
                pose(ID.id(),ID.generation(),0,0,2,.375f,2),pose(ID.id(),ID.generation(),0,0,1,.5f,2),
                pose(ID.id(),ID.generation(),0,0,1,.375f,Float.NaN)})
            assertThrows(IllegalArgumentException.class,()->restore(p));
    }
    @Test void mixedPublicationSupportsAllThreeReservedDomainsWithoutAddingParticleCapacity() {
        var input=new PackagePoseQueryGpu.Input(1,2,3,4,5,131072,393216,131072);assertEquals(131072,input.capacity());
        assertThrows(IllegalArgumentException.class,()->new PackagePoseQueryGpu.Input(1,2,3,4,5,131072,393217,131072));
        assertThrows(IllegalArgumentException.class,()->new PackagePoseQueryGpu.Input(1,2,3,4,5,131073,393216,131072));
    }
}
