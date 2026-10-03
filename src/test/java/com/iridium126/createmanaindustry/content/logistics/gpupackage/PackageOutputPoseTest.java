package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PackageOutputPoseTest {
    @Test void nativeDropImpulseConvertsTicksToSecondsWithoutLosingAnyComponent() {
        var pose=PackageOutputPose.dropped(new Vec3(63.99,15.75,-.001),new Vec3(.125,.125,-.5),-37);
        assertEquals(3.75f,pose.vx());assertEquals(3.75f,pose.vy());assertEquals(-15f,pose.vz());
        assertEquals(63.99,pose.x());assertEquals(15.75,pose.y());assertEquals(-.001,pose.z());assertEquals(-37,pose.yaw());
    }
    @Test void allOutletsSeparateFullPackageDimensionsAndPreserveImpulse() {
        var source=new AABB(-1,15,63,0,16,64);
        for(var side:Direction.values())for(float width:new float[]{.625f,.75f,1f})for(float height:new float[]{.5f,.625f,.75f,1f}) {
            var original=new PackageLease.Pose(-.5,15.25,63.5,3.75f,-7.5f,15,-93);
            var result=PackageOutputPose.clearSource(original,width,height,source,side);
            var body=new AABB(result.x()-width*.5,result.y(),result.z()-width*.5,result.x()+width*.5,result.y()+height,result.z()+width*.5);
            assertFalse(body.intersects(source),side+" "+width+"x"+height);
            assertEquals(original.vx(),result.vx());assertEquals(original.vy(),result.vy());assertEquals(original.vz(),result.vz());assertEquals(original.yaw(),result.yaw());
            assertSame(result,PackageOutputPose.clearSource(result,width,height,source,side),"Repeated admission must not keep moving the record");
        }
    }
    @Test void chuteFeetLeaveBelowTheSourceRatherThanTreatingFeetAsColliderCentre() {
        var result=PackageOutputPose.clearSource(new PackageLease.Pose(.5,-.25,.5,0,-7.5f,0,0),.75f,.75f,new AABB(0,0,0,1,1,1),Direction.DOWN);
        assertTrue(result.y()+.75<0);assertEquals(-7.5f,result.vy());
    }
    @Test void alreadySeparatedOutputsAndSourceOverhangsAreHandledWithoutRepeatedDisplacement() {
        var source=new AABB(0,0,-.125,1,1.5,1.125);
        var separated=new PackageLease.Pose(.5,2,.5,0,4,0,0);
        assertSame(separated,PackageOutputPose.clearSource(separated,.75f,.75f,source,Direction.UP));
        var result=PackageOutputPose.clearSource(new PackageLease.Pose(.5,1,.5,0,4,0,0),.75f,.75f,source,Direction.UP);
        assertTrue(result.y()>1.5);assertEquals(4,result.vy());
    }
}
