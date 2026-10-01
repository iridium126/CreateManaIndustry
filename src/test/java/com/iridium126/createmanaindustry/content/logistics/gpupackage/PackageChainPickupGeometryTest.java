package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageChainPickupGeometryTest {
    private static PackageChainAuthority.Baseline base(float before,int mask) {
        return new PackageChainAuthority.Baseline(0,new PackageLease.Identity(1,1),1,2,0,9,
                new PackageChainAuthority.State(before,10,new PackageLease.Pose(0,0,0,0,0,0,0)),mask);
    }
    private static PackageNativeChainPlan.Node node(float position){return new PackageNativeChainPlan.Node(position,1,BlockPos.ZERO,"");}
    @Test void linearStateUsesServerGeometryAndDoesNotPassUnconfirmedGameplayNodes() {
        var track=new PackageChainTrack(new Vec3(-20,4096,30),new Vec3(-10,4096,30),0,10,20,false,false,-90,0,1,9);
        var nodes=List.of(node(6));var b=base(5,1);
        var state=PackageNativeChainPlan.pickupCheckpoint(track,nodes,b,6,10);
        assertNotNull(state);assertEquals(-14,state.pose().x(),1e-6);assertEquals(4096,state.pose().y());assertEquals(-90,state.pose().yaw());
        assertNull(PackageNativeChainPlan.pickupCheckpoint(track,nodes,b,6.1f,10));
        assertNotNull(PackageNativeChainPlan.pickupCheckpoint(track,nodes,base(5,0),7,10));
        for(float p:new float[]{4,8,11,Float.NaN})assertNull(PackageNativeChainPlan.pickupCheckpoint(track,nodes,b,p,10));
        assertNull(PackageNativeChainPlan.pickupCheckpoint(track,nodes,b,5,9));
    }
    @Test void bothLoopDirectionsHandleWrapAndEligibleCrossings() {
        for(boolean reversed:new boolean[]{false,true}) {
            var track=new PackageChainTrack(new Vec3(100,4096,200),Vec3.ZERO,.875f,0,reversed?-90:90,true,reversed,0,0,1,9);
            float before=reversed?1:359,after=reversed?359:1;
            assertNull(PackageNativeChainPlan.pickupCheckpoint(track,List.of(node(0)),base(before,1),after,10));
            var state=PackageNativeChainPlan.pickupCheckpoint(track,List.of(node(0)),base(before,0),after,10);assertNotNull(state);
            assertEquals(100+Math.sin(Math.toRadians(after))*.875,state.pose().x(),1e-9);
            assertEquals(after+(reversed?180:0),state.pose().yaw());
            assertNull(PackageNativeChainPlan.pickupCheckpoint(track,List.of(node(0)),base(before,0),180,10));
            assertNull(PackageNativeChainPlan.pickupCheckpoint(track,List.of(node(0)),base(before,0),360,10));
        }
    }
    @Test void stationaryChainCannotInventMovementAndElapsedAllowanceRemainsBounded() {
        var stopped=new PackageChainTrack(Vec3.ZERO,Vec3.ZERO,.875f,0,0,true,false,0,0,0,9);
        assertNotNull(PackageNativeChainPlan.pickupCheckpoint(stopped,List.of(),base(10,0),10,100));
        assertNull(PackageNativeChainPlan.pickupCheckpoint(stopped,List.of(),base(10,0),11,100));
        var moving=new PackageChainTrack(Vec3.ZERO,new Vec3(100,0,0),0,100,20,false,false,0,0,0,9);
        assertNotNull(PackageNativeChainPlan.pickupCheckpoint(moving,List.of(),base(10,0),15,13));
        assertNull(PackageNativeChainPlan.pickupCheckpoint(moving,List.of(),base(10,0),15.01f,13));
    }
}
