package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PackageChainTrackTest {
    @Test void loopUsesCreateConveyorRadiusAndAngularRate() {
        var track=PackageChainTrack.loop(new BlockPos(-20,2048,30),-180,4,3,7);
        assertEquals(new Vec3(-19.5,2048.375,30.5),track.start());assertEquals(.875f,track.radius());
        assertEquals((-180/360f/((float)Math.PI*1.5f))*360*20,track.rate());assertTrue(track.reversed());
    }
    @Test void stoppedLoopHasZeroRate() {
        var track=PackageChainTrack.loop(BlockPos.ZERO,0,0,0,1);assertEquals(0,track.rate());assertFalse(track.reversed());
    }
    @Test void serializationPreservesPositionsOffsetsAndLongRevision() {
        var track=PackageChainTrack.loop(new BlockPos(-20,2048,30),32,17,32,0x102030405L);
        var storage=ByteBuffer.allocateDirect(80).order(ByteOrder.nativeOrder());storage.position(8).limit(72);
        track.write(storage,-32,2048,16);assertEquals(8,storage.position());assertEquals(72,storage.limit());
        assertEquals(12.5f,storage.getFloat(8));assertEquals(.375f,storage.getFloat(12));assertEquals(14.5f,storage.getFloat(16));
        assertEquals(17,storage.getInt(56));assertEquals(32,storage.getInt(60));assertEquals(0x102030405L,storage.getLong(64));
    }
    @Test void invalidHeadersAndOutputsDoNotModifyStorage() {
        assertThrows(IllegalArgumentException.class,()->PackageChainTrack.loop(BlockPos.ZERO,Float.NaN,0,0,1));
        assertThrows(IllegalArgumentException.class,()->PackageChainTrack.loop(BlockPos.ZERO,1,0,33,1));
        assertThrows(IllegalArgumentException.class,()->PackageChainTrack.loop(BlockPos.ZERO,1,0,0,0));
        var output=ByteBuffer.allocateDirect(64);output.putLong(0,123);
        var track=PackageChainTrack.loop(BlockPos.ZERO,1,0,0,1);
        assertThrows(IllegalArgumentException.class,()->track.write(output,Double.NaN,0,0));assertEquals(123,output.getLong(0));
    }
}
