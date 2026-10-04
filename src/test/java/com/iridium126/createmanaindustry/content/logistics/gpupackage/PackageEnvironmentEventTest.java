package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.nio.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class PackageEnvironmentEventTest {
    @Test void coalescedRunsPreserveDurationAndRejectOverlapOrOversizedRuns(){
        var b=event();b.putInt(24,3).putLong(64+16,140).putInt(64+44,40);
        var sample=PackageEnvironmentEvent.decode(b.array()).samples().getFirst();assertEquals(40,sample.ticks());assertEquals(140,sample.step());
        b.putInt(24,4).putLong(112+16,150).putInt(112+44,20);assertThrows(IllegalArgumentException.class,()->PackageEnvironmentEvent.decode(b.array()));
        b.putInt(24,3).putInt(64+44,10001);assertThrows(IllegalArgumentException.class,()->PackageEnvironmentEvent.decode(b.array()));
    }
    private static ByteBuffer event(){
        var b=ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(0,91).putLong(8,7).putLong(16,3).putInt(24,22).putInt(28,2).putInt(40,18).putLong(56,9);
        for(int i=0;i<20;i++){int p=64+i*48;b.putInt(p,-12).putInt(p+4,3).putInt(p+8,14).putInt(p+12,i==19?1:4).putLong(p+16,100+i).putInt(p+24,100).putFloat(p+28,5).putFloat(p+32,1).putFloat(p+36,2).putFloat(p+40,3);}
        return b;
    }
    @Test void preservesExactLifecycleAndEveryPendingSample(){
        var e=PackageEnvironmentEvent.decode(event().array());assertEquals(new PackageLease.Identity(91,7),e.identity());assertEquals(3,e.lease());assertEquals(17,e.index());assertEquals(9,e.revision());assertEquals(2,e.first());assertEquals(22,e.through());assertEquals(20,e.samples().size());assertEquals(119,e.samples().getLast().step());assertEquals(1,e.samples().getLast().contact());
        assertThrows(UnsupportedOperationException.class,()->e.samples().clear());
    }
    @Test void rejectsTruncationOverflowNonfiniteAndDuplicateSteps(){
        assertThrows(IllegalArgumentException.class,()->PackageEnvironmentEvent.decode(new byte[1023]));
        var b=event();b.putInt(24,23);byte[] overflow=b.array();assertThrows(IllegalArgumentException.class,()->PackageEnvironmentEvent.decode(overflow));
        b=event();b.putFloat(64+28,Float.NaN);byte[] nonfinite=b.array();assertThrows(IllegalArgumentException.class,()->PackageEnvironmentEvent.decode(nonfinite));
        b=event();b.putLong(112+16,100);byte[] duplicate=b.array();assertThrows(IllegalArgumentException.class,()->PackageEnvironmentEvent.decode(duplicate));
    }
    @Test void resolvesContactBlockFromSignedRegionOrigin(){
        var sample=PackageEnvironmentEvent.decode(event().array()).samples().getFirst();
        assertEquals(new net.minecraft.core.BlockPos(628,-125,1294),sample.block(new PackageRegion(10,-2,20)));
        assertEquals(new net.minecraft.core.BlockPos(-12,3,14),sample.block(new PackageRegion(0,0,0)));
    }
    @Test void gpuPayloadUsesOneRegionOriginForBlocksAndBodyPositions(){
        for(var region:new PackageRegion[]{new PackageRegion(10,2,-20),new PackageRegion(-10,-2,20),new PackageRegion(0,0,0)}) {
            var raw=event();int ox=672,oy=144,oz=-1248;
            var converted=PackageEnvironmentEvent.decode(PackageEnvironmentEvent.regionPayload(raw,ox,oy,oz,region));
            assertEquals(0,raw.position());assertEquals(-12,raw.getInt(64));
            assertEquals(20,converted.samples().size());
            for(var sample:converted.samples()) {
                assertEquals(new net.minecraft.core.BlockPos(ox-12,oy+3,oz+14),sample.block(region));
                assertEquals(ox+1,region.originX()+sample.px());
                assertEquals(oy+2,region.originY()+sample.py());
                assertEquals(oz+3,region.originZ()+sample.pz());
            }
            assertEquals(91,converted.identity().id());assertEquals(7,converted.identity().generation());
            assertEquals(3,converted.lease());assertEquals(9,converted.revision());
        }
    }
}
