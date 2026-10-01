package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackagePoseDecodeTest {
    private static final PackagePoseQueryGpu.Input INPUT=new PackagePoseQueryGpu.Input(1,2,3,4,5,65,128,131072);
    private static ByteBuffer pose() {
        var b=ByteBuffer.allocate(128).order(ByteOrder.nativeOrder());
        b.putLong(0,0x100000001L).putLong(8,0x200000003L).putInt(16,64).putInt(20,127).putInt(24,1).putInt(28,5);
        b.putFloat(32,3).putFloat(60,0).putFloat(96,2);return b;
    }
    @Test void decodesCompleteLongIdentityAndPreviousPose() {
        var value=PackagePoseQueryGpu.decode(pose(),0,INPUT);
        assertEquals(0x100000001L,value.id());assertEquals(0x200000003L,value.generation());
        assertEquals(64,value.candidate());assertEquals(127,value.body());assertEquals(3,value.x());assertEquals(2,value.px());
    }
    @Test void rejectsNonFiniteUncommittedOrOutOfBoundsRecords() {
        for(int offset:new int[]{32,60,96,124}){var b=pose().putFloat(offset,Float.NaN);assertThrows(IllegalStateException.class,()->PackagePoseQueryGpu.decode(b,0,INPUT));}
        for(int offset:new int[]{16,20,28}){var b=pose().putInt(offset,Integer.MAX_VALUE);assertThrows(IllegalStateException.class,()->PackagePoseQueryGpu.decode(b,0,INPUT));}
        var prepared=pose().putFloat(60,-2);assertThrows(IllegalStateException.class,()->PackagePoseQueryGpu.decode(prepared,0,INPUT));
        var hidden=pose().putInt(24,5);assertThrows(IllegalStateException.class,()->PackagePoseQueryGpu.decode(hidden,0,INPUT));
    }
    @Test void emptyRecordMustBeEntirelyZero() {
        var b=ByteBuffer.allocate(128).order(ByteOrder.nativeOrder());assertEquals(PackagePoseQueryGpu.Result.NONE,PackagePoseQueryGpu.decode(b,0,INPUT));
        b.putInt(124,1);assertThrows(IllegalStateException.class,()->PackagePoseQueryGpu.decode(b,0,INPUT));
    }
}
