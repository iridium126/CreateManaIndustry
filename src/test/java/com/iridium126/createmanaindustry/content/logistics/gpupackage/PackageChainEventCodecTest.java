package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageChainEventCodecTest {
    private static ByteBuffer records(int n) {
        var data=ByteBuffer.allocate(n*64).order(ByteOrder.nativeOrder());
        for(int i=0;i<n;i++) {
            int p=i*64;
            data.putLong(p,Long.MAX_VALUE-i).putLong(p+8,Long.MAX_VALUE).putInt(p+16,i*3)
                    .putLong(p+20,Long.MAX_VALUE).putInt(p+28,-1).putInt(p+32,Integer.MIN_VALUE).putInt(p+36,-1)
                    .putInt(p+40,131071).putInt(p+44,3).putFloat(p+48,359.9f).putFloat(p+52,1.9f).putFloat(p+56,-12345.5f);
        }
        return data;
    }
    private static ByteBuffer wire(ByteBuffer raw) {
        var wire=ByteBuffer.allocate(PackageChainEventCodec.MAX_WIRE_BYTES);
        PackageChainEventCodec.encode(raw,raw.remaining()/64,wire,new long[PackageChainEventCodec.BATCH]);return wire;
    }
    @Test void exactFullWidthIdentityMasksAndFloatBitsRoundTripWithinWireLimit() {
        var original=records(256);var wire=wire(original);assertTrue(wire.remaining()<24576);
        var decoded=ByteBuffer.allocate(256*64).order(ByteOrder.nativeOrder());
        assertEquals(256,PackageChainEventCodec.decode(wire,decoded));decoded.flip();assertEquals(original,decoded);
        assertEquals(0,original.position());assertEquals(0,wire.position());
    }
    @Test void unorderedGpuScatterIsSortedWithoutMutatingItsAckRecords() {
        var raw=records(3);byte[] first=new byte[64],last=new byte[64];raw.get(0,first);raw.get(128,last);raw.put(0,last).put(128,first);
        var decoded=ByteBuffer.allocate(3*64).order(ByteOrder.nativeOrder());PackageChainEventCodec.decode(wire(raw),decoded);decoded.flip();
        assertEquals(records(3),decoded);assertEquals(6,raw.getInt(16));
    }
    @Test void fallbackWithoutValidTrackRevisionRoundTrips() {
        var raw=records(1);raw.putLong(20,0).putInt(32,0).putInt(36,0).putInt(44,PackageChainEventCodec.FALLBACK);
        var out=ByteBuffer.allocate(64);PackageChainEventCodec.decode(wire(raw),out);out.flip();assertEquals(raw,out);
    }
    @Test void invalidGpuFieldsAndDuplicateCandidatesNeverEncode() {
        for(int field:new int[]{0,8,20,28,44,48,60}) {
            var raw=records(1);
            if(field<=20)raw.putLong(field,0);else if(field==48)raw.putFloat(field,Float.NaN);else raw.putInt(field,field==28?0:7);
            assertThrows(IllegalArgumentException.class,()->wire(raw));
        }
        var duplicate=records(2);duplicate.putInt(64+16,0);assertThrows(IllegalArgumentException.class,()->wire(duplicate));
    }
    @Test void truncatedMalformedAndTrailingPacketsCannotAdvanceOutputPosition() {
        var wire=wire(records(1));var out=ByteBuffer.allocate(64);
        for(int length=0;length<wire.remaining();length++) {
            var truncated=wire.duplicate();truncated.limit(length);
            assertThrows(IllegalArgumentException.class,()->PackageChainEventCodec.decode(truncated,out));assertEquals(0,out.position());
        }
        var trailing=ByteBuffer.allocate(wire.remaining()+1);trailing.put(wire.duplicate()).put((byte)0).flip();
        assertThrows(IllegalArgumentException.class,()->PackageChainEventCodec.decode(trailing,out));
        for(byte[] invalid:new byte[][]{{0},{(byte)0x81,0},{(byte)0x80,(byte)0x80},{(byte)0xff,(byte)0xff}})
            assertThrows(IllegalArgumentException.class,()->PackageChainEventCodec.decode(ByteBuffer.wrap(invalid),out));
        assertEquals(0,out.position());
    }
    @Test void callerOffsetsByteOrderAndInsufficientStorageAreHandled() {
        var raw=records(1);var wire=wire(raw);var target=ByteBuffer.allocate(96).order(ByteOrder.BIG_ENDIAN);target.position(16);
        assertEquals(1,PackageChainEventCodec.decode(wire,target));assertEquals(80,target.position());
        target.position(16).limit(80);assertEquals(raw,target.slice());
        assertThrows(IllegalArgumentException.class,()->PackageChainEventCodec.decode(wire,ByteBuffer.allocate(63)));
    }
}
