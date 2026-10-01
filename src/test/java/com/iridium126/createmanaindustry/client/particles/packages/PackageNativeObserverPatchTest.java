package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.*;
import org.junit.jupiter.api.Test;

class PackageNativeObserverPatchTest {
    static ByteBuffer data(int n){return ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder());}
    @Test void baselineRetainsRawDoubleBitsAndFullNamespace() {
        var b=data(136);b.putInt(0,123).putInt(132,456);b.position(4);b.limit(132);
        double x=Math.nextDown(.5/4096);
        PackageNativeObserverPatch.baseline(b,131071,-7,0x1234567800000001L,0x2345678900000001L,0x3456789000000001L,
                0x4567890100000001L,x,-29999990.001,29999990.002,.01,-.02,.03,1,.75f,-179,true,2.5f);
        assertEquals(132,b.position());assertEquals(0x1234567800000001L,b.getLong(4));assertEquals(1,b.getLong(28));
        assertEquals(-7,b.getInt(40));assertEquals(Double.doubleToRawLongBits(x),b.getLong(52));
        assertEquals(-.02,b.getDouble(84));assertEquals(-179,b.getFloat(108));assertEquals(2.5f,b.getFloat(112));
        assertEquals(0x4567890100000001L,b.getLong(116));assertEquals(0,b.getLong(124));
        b.limit(136);assertEquals(123,b.getInt(0));assertEquals(456,b.getInt(132));
    }
    @Test void rawRelativeAndVelocityCommandsNeverMergePoseOnCpu() {
        var b=data(128);
        PackageNativeObserverPatch.move(b,3,-8,9,10,Short.MIN_VALUE,(short)0,Short.MAX_VALUE,true,true,(byte)-128,true,.05f);
        assertEquals(64,b.position());assertEquals(9,b.getLong(0));assertEquals(10,b.getLong(8));
        assertEquals(0x8007,b.getInt(28));assertEquals(-32768,b.getInt(32));assertEquals(0,b.getInt(36));
        assertEquals(32767,b.getInt(40));assertEquals(0,b.getInt(44));assertEquals(.05f,b.getFloat(56));assertEquals(0,b.getInt(60));
        PackageNativeObserverPatch.motion(b,3,-8,9,11,-31200,0,31200,.05f);
        assertEquals(128,b.position());assertEquals(4,b.getInt(88));assertEquals(-31200,b.getInt(96));assertEquals(31200,b.getInt(104));
    }
    @Test void invalidInputDoesNotOverwriteRetainedCommand() {
        var b=data(64);b.putLong(0,123);
        assertThrows(IllegalArgumentException.class,()->PackageNativeObserverPatch.motion(b,0,1,1,0,32768,0,0,0));
        assertThrows(IllegalArgumentException.class,()->PackageNativeObserverPatch.release(b,0,1,0,0,0));
        assertThrows(IllegalArgumentException.class,()->PackageNativeObserverPatch.teleport(b,0,1,1,0,Double.NaN,0,0,(byte)0,false,0));
        assertEquals(123,b.getLong(0));assertEquals(0,b.position());
        assertThrows(IllegalArgumentException.class,()->PackageNativeObserverPatch.release(ByteBuffer.allocate(64),0,1,1,0,0));
    }
}
