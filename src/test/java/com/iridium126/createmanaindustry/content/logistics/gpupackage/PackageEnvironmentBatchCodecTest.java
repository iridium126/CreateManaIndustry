package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.nio.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageEnvironmentBatchCodecTest {
    private static ByteBuffer record(long id) {
        var b=ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(0,id).putLong(8,7).putLong(16,3).putInt(24,1).putInt(40,18).putLong(56,9);
        b.putLong(80,100).putFloat(92,5).putFloat(96,1).putFloat(100,2).putFloat(104,3).putInt(108,1);
        return b;
    }
    @Test void maximumBatchRetainsOrderAndEveryLifecycleWithoutConsumingBorrowedInput() {
        var input=ByteBuffer.allocate(7+PackageEnvironmentBatchCodec.MAX_BYTES+3);
        input.position(7);for(int i=1;i<=24;i++)input.put(record(i));input.flip().position(7);
        var events=PackageEnvironmentBatchCodec.decode(input.asReadOnlyBuffer());
        assertEquals(7,input.position());assertEquals(24,events.size());
        for(int i=0;i<24;i++) {
            assertEquals(i+1,events.get(i).identity().id());assertEquals(7,events.get(i).identity().generation());
            assertEquals(3,events.get(i).lease());assertEquals(17,events.get(i).index());
            assertEquals(9,events.get(i).revision());assertEquals(100,events.get(i).samples().getFirst().step());
        }
        assertThrows(UnsupportedOperationException.class,events::clear);
    }
    @Test void malformedTailRejectsTheWholeBatchBeforeAnyTransactionCanRun() {
        var input=ByteBuffer.allocate(2048);input.put(record(1)).put(record(2)).flip();
        input.order(ByteOrder.LITTLE_ENDIAN).putFloat(1024+96,Float.NaN);
        int[] applied={0};
        assertThrows(IllegalArgumentException.class,()->{
            for(var event:PackageEnvironmentBatchCodec.decode(input))applied[0]++;
        });
        assertEquals(0,applied[0]);assertEquals(0,input.position());
        for(int size:new int[]{0,1,1023,1025,24577,25600})
            assertThrows(IllegalArgumentException.class,()->PackageEnvironmentBatchCodec.decode(ByteBuffer.allocate(size)),"size="+size);
    }
}
