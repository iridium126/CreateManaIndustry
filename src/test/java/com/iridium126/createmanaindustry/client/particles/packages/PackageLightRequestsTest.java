package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class PackageLightRequestsTest {
    private static ByteBuffer bytes(int count){var result=ByteBuffer.allocate(PackageLightRequests.BYTES).order(ByteOrder.nativeOrder());result.putInt(0,count);return result;}
    @Test void validatesAllCoordinatesBeforeAnyRequestsAndPreservesPosition() {
        var data=bytes(2);data.putInt(16,-1).putInt(20,4000).putInt(24,2);data.putInt(32,3).putInt(36,Integer.MAX_VALUE);
        var received=new ArrayList<PackageCollisionCache.Section>();var decoder=new PackageLightRequests();
        assertThrows(IllegalArgumentException.class,()->decoder.consume(data,received::add));assertTrue(received.isEmpty());
        data.putInt(36,-4000);assertEquals(2,decoder.consume(data,received::add));assertEquals(0,data.position());
        assertEquals(new PackageCollisionCache.Section(-1,4000,2),received.getFirst());assertEquals(new PackageCollisionCache.Section(3,-4000,0),received.getLast());
    }
    @Test void overflowProcessesBoundedPrefixAndLeavesCountExactForRetryStatistics() {
        var received=new ArrayList<PackageCollisionCache.Section>();var data=bytes(1024);
        for(int i=0;i<256;i++)data.putInt(16+i*16,i);
        assertEquals(1024,new PackageLightRequests().consume(data,received::add));assertEquals(256,received.size());
    }
    @Test void malformedHeaderLengthAndReservedWordsAreRejected() {
        var decoder=new PackageLightRequests();var data=bytes(0);assertEquals(0,decoder.consume(data,s->fail()));
        for(int count:new int[]{-1,1025}){data.putInt(0,count);assertThrows(IllegalArgumentException.class,()->decoder.consume(data,s->fail()));}
        data.putInt(0,1);data.putInt(28,1);assertThrows(IllegalArgumentException.class,()->decoder.consume(data,s->fail()));
        data.putInt(28,0);data.putInt(4,1);assertThrows(IllegalArgumentException.class,()->decoder.consume(data,s->fail()));
        assertThrows(IllegalArgumentException.class,()->decoder.consume(ByteBuffer.allocate(16),s->fail()));
    }
    @Test void callbackFailureCanRetryExactSnapshot() {
        var data=bytes(1);var decoder=new PackageLightRequests();assertThrows(IllegalStateException.class,()->decoder.consume(data,s->{throw new IllegalStateException();}));
        var received=new ArrayList<PackageCollisionCache.Section>();assertEquals(1,decoder.consume(data,received::add));assertEquals(1,received.size());
    }
}
