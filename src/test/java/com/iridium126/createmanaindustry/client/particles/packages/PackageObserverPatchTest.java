package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import org.junit.jupiter.api.Test;

class PackageObserverPatchTest {
    private static final PackageLease.Identity ID=new PackageLease.Identity(0x1234567800000001L,0x2345678900000001L);
    private static final PackageDeltaCodec.Quantized STATE=new PackageDeltaCodec.Quantized(123456,234567,34567,32768,0,0,(short)-12345,3);
    @Test void layoutPreservesEveryIdentityBitAndCallerSliceSentinels() {
        var bytes=ByteBuffer.allocateDirect(PackageObserverGpu.PATCH_BYTES+8).order(ByteOrder.nativeOrder());
        bytes.putInt(0,0x12345678).putInt(bytes.capacity()-4,0x23456789);bytes.position(4);bytes.limit(bytes.capacity()-4);
        PackageObserverPatch.baseline(bytes,131071,65536,ID,0x3456789000000001L,0x4567890100000001L,0x5678901200000001L,
                STATE,-64,128,-192,1,.75f,2.5f);
        assertEquals(4,bytes.position());assertEquals(ID.id(),bytes.getLong(4));assertEquals(ID.generation(),bytes.getLong(12));
        assertEquals(0x3456789000000001L,bytes.getLong(20));assertEquals(0x4567890100000001L,bytes.getLong(28));
        assertEquals(131071,bytes.getInt(36));assertEquals(65536,bytes.getInt(40));assertEquals(32,bytes.getInt(44));
        assertEquals(STATE.y(),bytes.getInt(56));assertEquals(0,bytes.getInt(72));assertEquals(-12345,bytes.getInt(80));
        assertEquals(-64,bytes.getFloat(84));assertEquals(2.5f,bytes.getFloat(96));assertEquals(.375f,bytes.getFloat(104));
        assertEquals(0x5678901200000001L,bytes.getLong(116));assertEquals(0,bytes.getLong(124));
        bytes.limit(bytes.capacity());assertEquals(0x12345678,bytes.getInt(0));assertEquals(0x23456789,bytes.getInt(bytes.capacity()-4));
    }
    @Test void partialFieldsAndReleaseAreCopiedWithoutMergingOnCpu() {
        var bytes=ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        var change=new PackageDeltaCodec.Entry(7,PackageDeltaCodec.VELOCITY,STATE);
        PackageObserverPatch.delta(bytes,3,ID,9,10,11,change,1);
        assertEquals(2,bytes.getInt(40));assertEquals(0,bytes.getFloat(80));assertEquals(0,bytes.getFloat(96));
        assertEquals(STATE.x(),bytes.getInt(48));assertEquals(STATE.vx(),bytes.getInt(64));
        PackageObserverPatch.delta(bytes,3,ID,9,10,12,new PackageDeltaCodec.Entry(7,PackageDeltaCodec.RELEASE,STATE),2);
        assertEquals(16,bytes.getInt(40));assertEquals(12,bytes.getLong(112));
    }
    @Test void malformedLayoutAndGeometryFailBeforeMutatingOutput() {
        var bytes=ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());bytes.putInt(0,0x12345678);
        assertThrows(IllegalArgumentException.class,()->PackageObserverPatch.baseline(bytes,0,0,ID,1,1,0,STATE,0,0,0,3,1,0));
        assertEquals(0x12345678,bytes.getInt(0));
        assertThrows(IllegalArgumentException.class,()->PackageObserverPatch.baseline(bytes,0,0,ID,1,1,0,STATE,8192,0,0,1,1,0));
        assertThrows(IllegalArgumentException.class,()->PackageObserverPatch.delta(bytes,-1,ID,1,1,0,new PackageDeltaCodec.Entry(0,1,STATE),0));
        assertThrows(IllegalArgumentException.class,()->PackageObserverPatch.baseline(ByteBuffer.allocate(128),0,0,ID,1,1,0,STATE,0,0,0,1,1,0));
        bytes.order(ByteOrder.nativeOrder()==ByteOrder.BIG_ENDIAN?ByteOrder.LITTLE_ENDIAN:ByteOrder.BIG_ENDIAN);
        assertThrows(IllegalArgumentException.class,()->PackageObserverPatch.baseline(bytes,0,0,ID,1,1,0,STATE,0,0,0,1,1,0));
    }
    @Test void compactLayoutRetainsNamespaceAndWideVelocityWithoutCpuPoseMath() {
        var bytes=ByteBuffer.allocateDirect(88).order(ByteOrder.nativeOrder());
        bytes.putInt(0,0x12345678).putInt(84,0x23456789);bytes.position(4);bytes.limit(84);
        PackageObserverPatch.compactDelta(bytes,131071,0x3456789000000001L,0x4567890100000001L,0x5678901200000001L,
                new PackageDeltaCodec.Entry(65536,15,STATE),2.5f);
        assertEquals(4,bytes.position());assertEquals(0x3456789000000001L,bytes.getLong(4));
        assertEquals(0x4567890100000001L,bytes.getLong(12));assertEquals(131071,bytes.getInt(20));
        assertEquals(65536,bytes.getInt(24));assertEquals(15,bytes.getInt(28));assertEquals(2.5f,bytes.getFloat(32));
        assertEquals(STATE.x(),bytes.getInt(36));assertEquals(STATE.flags(),bytes.getInt(48));
        assertEquals(STATE.vx(),bytes.getInt(52));assertEquals(STATE.vy(),bytes.getInt(56));
        assertEquals(STATE.vz(),bytes.getInt(60));assertEquals(STATE.yaw(),bytes.getInt(64));
        assertEquals(0x5678901200000001L,bytes.getLong(68));assertEquals(0,bytes.getLong(76));
        bytes.limit(88);assertEquals(0x12345678,bytes.getInt(0));assertEquals(0x23456789,bytes.getInt(84));
    }
    @Test void malformedCompactLayoutDoesNotPartiallyWriteNamespace() {
        var bytes=ByteBuffer.allocateDirect(72).order(ByteOrder.nativeOrder());bytes.putInt(0,0x12345678);
        assertThrows(IllegalArgumentException.class,()->PackageObserverPatch.compactDelta(bytes,0,1,0,0,new PackageDeltaCodec.Entry(0,1,STATE),0));
        assertThrows(IllegalArgumentException.class,()->PackageObserverPatch.compactDelta(bytes,0,1,1,0,new PackageDeltaCodec.Entry(0,1,STATE),Float.NaN));
        bytes.limit(71);assertThrows(IllegalArgumentException.class,()->PackageObserverPatch.compactDelta(bytes,0,1,1,0,new PackageDeltaCodec.Entry(0,1,STATE),0));
        assertEquals(0x12345678,bytes.getInt(0));
    }
}
