package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import com.iridium126.createmanaindustry.client.particles.packages.PackageChainUpload;
import com.iridium126.createmanaindustry.client.particles.packages.PackagePhysicsGpu;
import com.iridium126.createmanaindustry.client.particles.packages.PackagePoseQueryGpu;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageChainGpuFrameTest {
    private static PackageChainSpace.Frame frame(UUID parent,Vec3 local,Vec3 world) {
        return new PackageChainSpace.Frame(parent,local,world,new Vec3(0,0,-2),new Vec3(0,3,0),new Vec3(4,0,0));
    }
    @Test void rowsPreserveIndependentRenderAndLogicalPosesAroundLargeOrigins() {
        var parent=UUID.randomUUID();var local=new Vec3(1000000.5,Integer.MAX_VALUE-64.5,-1000000.5);
        var render=frame(parent,local,new Vec3(29999984.125,32.25,-29999984.5));
        var logical=frame(parent,local,render.worldOrigin().add(.25,-.5,1));
        var gpu=new PackageChainGpuFrame(render,logical);
        var out=ByteBuffer.allocateDirect(112).order(ByteOrder.nativeOrder());
        for(int i=0;i<out.capacity();i++)out.put(i,(byte)0x5a);
        out.position(8).limit(104);gpu.write(out,29999984,32,-29999984);
        assertEquals(8,out.position());assertEquals(104,out.limit());
        for(int i=0;i<8;i++){assertEquals((byte)0x5a,out.get(i));assertEquals((byte)0x5a,out.duplicate().clear().get(104+i));}
        var point=new Vec3(.125,.25,-.5);
        for(int pose=0;pose<2;pose++) {
            int p=8+pose*48;var f=pose==0?render:logical;
            var expected=f.world(f.localOrigin().add(point)).subtract(29999984,32,-29999984);
            for(int axis=0;axis<3;axis++) {
                int row=p+axis*16;
                double actual=out.getFloat(row)*point.x+out.getFloat(row+4)*point.y+out.getFloat(row+8)*point.z+out.getFloat(row+12);
                assertEquals(axis==0?expected.x:axis==1?expected.y:expected.z,actual,1e-6);
            }
        }
        assertEquals(96,PackageChainGpuFrame.BYTES);
    }
    @Test void invalidOriginOrLayoutCannotPublishAPartialFrame() {
        var f=frame(null,Vec3.ZERO,Vec3.ZERO);var gpu=new PackageChainGpuFrame(f,f);
        var out=ByteBuffer.allocateDirect(96);
        for(int i=0;i<96;i++)out.put(i,(byte)0x5a);
        for(double origin:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,()->gpu.write(out,origin,0,0));
            for(int i=0;i<96;i++)assertEquals((byte)0x5a,out.get(i));
        }
        assertThrows(IllegalArgumentException.class,()->gpu.write(ByteBuffer.allocate(96),0,0,0));
        assertThrows(IllegalArgumentException.class,()->gpu.write(out.asReadOnlyBuffer(),0,0,0));
        assertThrows(IllegalArgumentException.class,()->gpu.write(out.duplicate().limit(95),0,0,0));
    }
    @Test void renderAndLogicalMustUseTheSameNativeTrackNamespace() {
        var f=frame(UUID.randomUUID(),Vec3.ZERO,Vec3.ZERO);
        assertThrows(IllegalArgumentException.class,()->new PackageChainGpuFrame(f,frame(UUID.randomUUID(),Vec3.ZERO,Vec3.ZERO)));
        assertThrows(IllegalArgumentException.class,()->new PackageChainGpuFrame(f,frame(f.parent(),new Vec3(1,0,0),Vec3.ZERO)));
        assertThrows(NullPointerException.class,()->new PackageChainGpuFrame(f,null));
    }
    @Test void framedRetirementRestoresNativeCoordinatesAndIgnoresTheFreeDomainOrigin() {
        var identity=new PackageLease.Identity(0x100000003L,0x200000005L);
        var track=new com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundChainPackagePacket.Track(3,
                net.minecraft.core.BlockPos.ZERO,null,new PackageChainTrack(Vec3.ZERO,Vec3.ZERO,.875f,0,90,true,true,0,0,0,9),java.util.List.of(),UUID.randomUUID());
        var pose=new PackagePoseQueryGpu.Result(identity.id(),identity.generation(),7,18,15,3,
                3,4,5,35,.125f,-.25f,.375f,PackagePhysicsGpu.RETIRED,10,11,12,43,40,42,90,1,
                2,3,4,30,9,10,11,42);
        var saved=PackageChainUpload.checkpoint(pose,identity,track,-30_000_000.25,32_000_000.5,-64.25);
        assertEquals(3.5,saved.pendulum().x());assertEquals(4.5,saved.pendulum().y());
        assertEquals(10.5,saved.hookX());assertEquals(12+1.0/16,saved.hookY());
        assertEquals(2.5,saved.previous().x());assertEquals(9.5,saved.previous().targetX());
        assertEquals(0.125f,saved.pendulum().vx());
    }
}
