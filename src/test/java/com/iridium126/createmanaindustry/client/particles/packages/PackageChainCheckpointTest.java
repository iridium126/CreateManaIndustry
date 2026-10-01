package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.List;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageChainTrack;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageLease;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundChainPackagePacket;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageChainCheckpointTest {
    private static final PackageLease.Identity ID=new PackageLease.Identity(0x100000003L,0x200000005L);
    private static final ClientboundChainPackagePacket.Track TRACK=new ClientboundChainPackagePacket.Track(3,BlockPos.ZERO,null,
            new PackageChainTrack(Vec3.ZERO,Vec3.ZERO,.875f,0,90,true,true,0,0,0,9),List.of());
    private static PackagePoseQueryGpu.Result pose(long id,long generation,int flags,float state,float progress,float previousX) {
        return new PackagePoseQueryGpu.Result(id,generation,7,18,flags,3,
                3,4,5,35,.125f,-.25f,.375f,state,10,11,12,43,40,progress,90,1,
                previousX,3,4,30,9,10,11,42);
    }
    private static PackagePoseQueryGpu.Result pose(){return pose(ID.id(),ID.generation(),7,PackagePhysicsGpu.RETIRED,42,2);}
    private static PackageChainUpload.Checkpoint restore(PackagePoseQueryGpu.Result value){return PackageChainUpload.checkpoint(value,ID,TRACK,-30_000_000.25,32_000_000.5,-64.25);}
    @Test void preservesDoubleOriginHookOffsetAndCreateTickVelocity() {
        var saved=restore(pose());
        assertEquals(-29_999_997.25,saved.pendulum().x());assertEquals(32_000_004.5,saved.pendulum().y());
        assertEquals(-29_999_990.25,saved.hookX());assertEquals(32_000_012.0625,saved.hookY());
        assertEquals(.125f,saved.pendulum().vx());assertEquals(-.25f,saved.pendulum().vy());assertEquals(.375f,saved.pendulum().vz());
        assertEquals(42,saved.progress());assertEquals(43,saved.targetYaw());assertEquals(35,saved.pendulum().yaw());
        assertEquals(-29_999_998.25,saved.previous().x());assertEquals(32_000_010.5,saved.previous().targetY());assertEquals(30,saved.previous().yaw());
    }
    @Test void delayedBareIndexCannotRestoreAnotherIdentityOrGeneration() {
        assertThrows(IllegalArgumentException.class,()->restore(pose(ID.id()+1,ID.generation(),7,-3,42,2)));
        assertThrows(IllegalArgumentException.class,()->restore(pose(ID.id(),ID.generation()+1,7,-3,42,2)));
        assertThrows(IllegalArgumentException.class,()->restore(PackagePoseQueryGpu.Result.NONE));
    }
    @Test void preparedVisibleAndWrongReversalStatesCannotBecomeRetiredCheckpoints() {
        for(float state:new float[]{PackagePhysicsGpu.PREPARED,0,-1,Float.NaN})
            assertThrows(IllegalArgumentException.class,()->restore(pose(ID.id(),ID.generation(),7,state,42,2)));
        for(int flags:new int[]{0,1,3,5,23})
            assertThrows(IllegalArgumentException.class,()->restore(pose(ID.id(),ID.generation(),flags,-3,42,2)));
    }
    @Test void rejectsInvalidProgressOrHistoryBeforeNativeMutation() {
        for(float progress:new float[]{-1,360,Float.NaN,Float.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,()->restore(pose(ID.id(),ID.generation(),7,-3,progress,2)));
        assertThrows(IllegalArgumentException.class,()->restore(pose(ID.id(),ID.generation(),7,-3,42,Float.NaN)));
    }
    @Test void restoredInterpolationUsesTheSameTwoPhysicsPoses() {
        var value=pose();var saved=restore(value);double origin=-30_000_000.25;
        for(double partial:new double[]{0,.25,.5,.75,1}) {
            double expected=origin+value.px()+(value.x()-value.px())*partial;
            double actual=saved.previous().x()+(saved.pendulum().x()-saved.previous().x())*partial;
            assertEquals(expected,actual,1e-8);
            assertEquals(value.previousYaw()+(value.yaw()-value.previousYaw())*partial,
                    saved.previous().yaw()+(saved.pendulum().yaw()-saved.previous().yaw())*partial,1e-6);
        }
    }
    @Test void emergencyAcceptsCompletedVisiblePoseWithoutAcknowledgingRetirement() {
        var visible=pose(ID.id(),ID.generation(),3,0,42,2);
        var saved=PackageChainUpload.retainedCheckpoint(visible,ID,TRACK,16,32,48);
        assertEquals(19,saved.pendulum().x());assertEquals(26,saved.hookX());assertEquals(.125f,saved.pendulum().vx());
        assertThrows(IllegalArgumentException.class,()->PackageChainUpload.checkpoint(visible,ID,TRACK,16,32,48));
        assertEquals(restore(pose()),PackageChainUpload.retainedCheckpoint(pose(),ID,TRACK,-30_000_000.25,32_000_000.5,-64.25));
    }
    @Test void emergencyStillRejectsPreparedHiddenAndForeignIdentity() {
        for(float state:new float[]{PackagePhysicsGpu.PREPARED,-1,Float.NaN,Float.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,()->PackageChainUpload.retainedCheckpoint(pose(ID.id(),ID.generation(),3,state,42,2),ID,TRACK,0,0,0));
        for(var invalid:List.of(pose(ID.id()+1,ID.generation(),3,0,42,2),pose(ID.id(),ID.generation()+1,3,0,42,2),pose(ID.id(),ID.generation(),7,0,42,2)))
            assertThrows(IllegalArgumentException.class,()->PackageChainUpload.retainedCheckpoint(invalid,ID,TRACK,0,0,0));
    }
}
