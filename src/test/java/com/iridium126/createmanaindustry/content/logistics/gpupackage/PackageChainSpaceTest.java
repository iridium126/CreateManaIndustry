package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import dev.ryanhcode.sable.companion.math.Pose3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PackageChainSpaceTest {
    private static Vec3 copy(Vector3d v){return new Vec3(v.x,v.y,v.z);}
    private static void equal(Vec3 a,Vec3 b,double epsilon){assertEquals(a.x,b.x,epsilon);assertEquals(a.y,b.y,epsilon);assertEquals(a.z,b.z,epsilon);}
    @Test void ordinaryTracksPreserveOriginalRegionAndRayIncludingSignedBoundaries() {
        for(int coordinate:new int[]{-129,-128,-65,-64,-1,0,63,64,127,128}) {
            var pos=new BlockPos(coordinate,4096,-coordinate);var frame=PackageChainSpace.stationary(pos);
            assertNull(frame.parent());assertEquals(new PackageRegion(Math.floorDiv(pos.getX(),64),64,Math.floorDiv(pos.getZ(),64)),frame.region());
            Vec3 from=new Vec3(pos.getX()+.2,pos.getY()+.4,pos.getZ()-2),to=from.add(0,0,4);
            var bounds=new AABB(pos);equal(from,frame.local(from),0);equal(to,frame.world(to),0);
            assertEquals(bounds.clip(from,to).isPresent(),frame.hits(bounds,from,to));
        }
        assertNull(PackageChainSpace.optionalBridge(false));
    }
    @Test void realSablePoseMatchesLargePlotOriginsArbitraryRotationScaleAndInverse() {
        var random=new Random(0x5ab1eC4);var id=new UUID(19,23);
        for(int i=0;i<400;i++) {
            var origin=new Vec3(20_480_000.5,2_147_483_600.5,-20_480_000.5);
            var pose=new Pose3d(new Vector3d(-30_000_000.25,4096.5,30_000_000.375),
                    new Quaterniond().rotationXYZ(random.nextDouble()*6,random.nextDouble()*6,random.nextDouble()*6),
                    new Vector3d(origin.x-2,origin.y+3,origin.z+4),
                    new Vector3d(.25+random.nextDouble()*3,.25+random.nextDouble()*3,.25+random.nextDouble()*3));
            var frame=PackageSableChainSpace.capture(id,origin,pose);assertEquals(id,frame.parent());
            var local=origin.add(random.nextDouble()*8-4,random.nextDouble()*8-4,random.nextDouble()*8-4);
            var world=copy(pose.transformPosition(new Vector3d(local.x,local.y,local.z),new Vector3d()));
            equal(world,frame.world(local),1e-6);equal(local,frame.local(world),1e-6);
            equal(copy(pose.transformPositionInverse(new Vector3d(world.x,world.y,world.z),new Vector3d())),frame.local(world),1e-6);
            assertEquals(PackageRegion.at(new PackageLease.Pose(frame.worldOrigin().x,frame.worldOrigin().y,frame.worldOrigin().z,0,0,0,0)),frame.region());
            var stable=frame.world(local);pose.position().add(10,20,30);pose.orientation().rotateXYZ(.3,.4,.5);
            equal(stable,frame.world(local),0);
        }
    }
    @Test void transformedReachRayUsesExactLocalBoxRatherThanExpandedWorldAabb() {
        double c=Math.sqrt(.5);var origin=new Vec3(20_000_000,4096,-20_000_000);
        var frame=new PackageChainSpace.Frame(new UUID(1,2),origin,Vec3.ZERO,new Vec3(c,0,-c),new Vec3(0,1,0),new Vec3(c,0,c));
        var bounds=new AABB(origin.x-.5,origin.y-.5,origin.z-.5,origin.x+.5,origin.y+.5,origin.z+.5);
        Vec3 from=new Vec3(.65,-3,.65),to=from.add(0,6,0);
        assertTrue(new AABB(-c,-.5,-c,c,.5,c).clip(from,to).isPresent());assertFalse(frame.hits(bounds,from,to));
        assertTrue(frame.hits(bounds,new Vec3(0,-3,0),new Vec3(0,3,0)));
        var scaled=new PackageChainSpace.Frame(new UUID(1,2),origin,new Vec3(64,128,-64),new Vec3(2*c,0,-2*c),new Vec3(0,3,0),new Vec3(.5*c,0,.5*c));
        assertTrue(scaled.hits(bounds,scaled.world(origin.add(0,0,-2)),scaled.world(origin.add(0,0,2))));
        assertFalse(scaled.hits(bounds,scaled.world(origin.add(1,0,-2)),scaled.world(origin.add(1,0,2))));
    }
    @Test void movingParentChangesInterestWithoutChangingNativeProgressOrGeometry() {
        var origin=new Vec3(20_480_000,4096,20_480_000);var pose=new Pose3d(new Vector3d(63.75,80,-64),
                new Quaterniond().rotationY(.4),new Vector3d(origin.x,origin.y,origin.z),new Vector3d(1));
        var first=PackageSableChainSpace.capture(new UUID(1,2),origin,pose);
        var track=new PackageChainTrack(origin,origin.add(8,0,0),0,8,20,false,false,0,0,0,1);
        var baseline=new PackageChainAuthority.Baseline(0,new PackageLease.Identity(1,1),1,2,0,1,
                new PackageChainAuthority.State(2,10,new PackageLease.Pose(origin.x+2,origin.y,origin.z,0,0,0,0)),0);
        var before=PackageNativeChainPlan.pickupCheckpoint(track,List.of(),baseline,3,10);assertNotNull(before);
        pose.position().add(1,0,0);var afterFrame=PackageSableChainSpace.capture(new UUID(1,2),origin,pose);
        assertEquals(new PackageRegion(0,1,-1),first.region());assertEquals(new PackageRegion(1,1,-1),afterFrame.region());
        assertEquals(before,PackageNativeChainPlan.pickupCheckpoint(track,List.of(),baseline,3,10));
        var center=new Vec3(before.pose().x(),before.pose().y()-9.0/16,before.pose().z());
        var bounds=new AABB(center,center).move(0,-.25,0).expandTowards(0,.5,0).inflate(.45);
        assertTrue(afterFrame.hits(bounds,afterFrame.world(center.add(0,0,-3)),afterFrame.world(center.add(0,0,3))));
    }
    @Test void invalidTransformsAreRejectedAndSharedBoundaryDoesNotLinkSableOrClient() throws Exception {
        Vec3 y=new Vec3(0,1,0),z=new Vec3(0,0,1);
        for(var invalid:new Vec3[]{Vec3.ZERO,new Vec3(Double.NaN,0,0),new Vec3(-1,0,0),new Vec3(1,1,0)})
            assertThrows(IllegalArgumentException.class,()->new PackageChainSpace.Frame(null,Vec3.ZERO,Vec3.ZERO,invalid,y,z));
        for(String name:new String[]{"PackageChainSpace","PackageChainSpace$Frame","PackageChainSpace$Bridge","PackageChainSpace$Optional","PackageChainAuthorityManager"}) {
            try(var in=getClass().getResourceAsStream(name+".class")) {
                assertNotNull(in);var bytecode=new String(in.readAllBytes(),StandardCharsets.ISO_8859_1);
                assertFalse(bytecode.contains("dev/ryanhcode/sable/"));assertFalse(bytecode.contains("net/minecraft/client/"));
            }
        }
        try(var in=getClass().getResourceAsStream("PackageSableChainSpace.class")) {
            assertNotNull(in);var bytecode=new String(in.readAllBytes(),StandardCharsets.ISO_8859_1);
            assertTrue(bytecode.contains("dev/ryanhcode/sable/"));assertFalse(bytecode.contains("java/lang/reflect/"));assertFalse(bytecode.contains("forName"));assertFalse(bytecode.contains("net/minecraft/client/"));
        }
    }
}
