package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.*;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import org.joml.Vector3d;
import org.joml.Quaterniond;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real companion API parity, including pivots, nonuniform scale and large plot/world origins. */
class PackageSableForceFrameTest {
    @Test void bvhBoundsInverseRowsAndWorldFlowMatchSableAtLargeCoordinates(){
        var random=new Random(0x51abfe);
        for(int sample=0;sample<300;sample++){
            double x=20480000.37,y=2147483600.64,z=-20480000.22;
            var pose=new Pose3d(new Vector3d(30000000+.125,80,-30000000+.25),
                    new Quaterniond().rotationXYZ(random.nextDouble()*6,random.nextDouble()*6,random.nextDouble()*6),
                    new Vector3d(x,y,z),new Vector3d(.25+random.nextDouble()*3,.25+random.nextDouble()*3,.25+random.nextDouble()*3));
            var frame=new PackageForceScene.Frame(PackageSablePose.capture(pose,x,y,z,new Vector3d()),x,y,z);
            var source=new PackageForceScene.Source(2,x-1,y-.5,z,x+4,y+2,z+1,x,y,z,2,1,0,0,8).framed(frame);
            var scene=PackageForceScene.bake(4,List.of(source),30000000,80,-30000000);var b=scene.data();
            assertEquals(1,scene.frames());assertEquals(160,b.remaining());assertEquals(6,b.getInt(44));
            var worldBounds=new BoundingBox3d(new AABB(source.x0(),source.y0(),source.z0(),source.x1(),source.y1(),source.z1())).transform(pose,new BoundingBox3d()).toMojang();
            assertEquals(worldBounds.minX-30000000,b.getFloat(0),3e-6);assertEquals(worldBounds.maxX-30000000,b.getFloat(16),3e-6);
            assertEquals(worldBounds.minY-80,b.getFloat(4),3e-6);assertEquals(worldBounds.maxY-80,b.getFloat(20),3e-6);
            assertEquals(worldBounds.minZ+30000000,b.getFloat(8),3e-6);assertEquals(worldBounds.maxZ+30000000,b.getFloat(24),3e-6);
            var flow=pose.transformNormal(new Vector3d(1,0,0),new Vector3d());
            assertEquals(flow.x,b.getFloat(80),2e-7);assertEquals(flow.y,b.getFloat(84),2e-7);assertEquals(flow.z,b.getFloat(88),2e-7);
            var local=new Vector3d(random.nextDouble()*8-4,random.nextDouble()*8-4,random.nextDouble()*8-4);
            var world=pose.transformPosition(new Vector3d(x+local.x,y+local.y,z+local.z),new Vector3d());
            var delta=world.sub(frame.pose().tx(),frame.pose().ty(),frame.pose().tz(),new Vector3d());
            for(int axis=0;axis<3;axis++)assertEquals(local.get(axis),frame.inverseRow(axis).dot(delta),2e-7);
            var captured=frame.project(x,y,z);pose.position().add(7,8,9);
            assertEquals(captured,frame.project(x,y,z),"mutable logical pose leaked into the worker snapshot");
        }
    }
    @Test void onlyFramedFansAllocateExtensionRecordsAndEntitySourcesUseWorldBounds(){
        var pose=new PackageMovingGeometry.Pose(0,1,0,-2,0,0,0,0,3,10,20,30);
        var frame=new PackageForceScene.Frame(pose,100,200,300);
        var entity=new PackageForceScene.Source(1,99,199,299,101,201,301,100,199,300,1,0,0,0,0).framed(frame);
        var nativeFan=new PackageForceScene.Source(2,0,0,0,1,1,1,0,0,0,1,1,0,0,4);
        var framedFan=new PackageForceScene.Source(2,99,199,299,101,201,301,100,200,300,1,1,0,0,4).framed(frame);
        var scene=PackageForceScene.bake(0,List.of(entity,nativeFan,framedFan),0,0,0);var b=scene.data();int base=scene.nodes()*32;
        assertEquals(1,scene.frames());assertEquals(5*32+3*64+64,b.remaining());assertEquals(1,b.getInt(base+12));assertEquals(2,b.getInt(base+64+12));assertEquals(6,b.getInt(base+128+12));
        assertEquals(8,b.getFloat(base));assertEquals(19,b.getFloat(base+4));assertEquals(27,b.getFloat(base+8));
        assertEquals(12,b.getFloat(base+16));assertEquals(21,b.getFloat(base+20));assertEquals(33,b.getFloat(base+24));
        assertEquals(12,b.getFloat(base+32));assertEquals(20,b.getFloat(base+36));assertEquals(30,b.getFloat(base+40));
    }
}
