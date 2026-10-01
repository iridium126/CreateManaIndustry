package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class PackageCollisionRequestsTest {
    private static ByteBuffer records(int count) {
        var bytes=ByteBuffer.allocate(PackageCollisionRequests.BYTES).order(ByteOrder.nativeOrder());bytes.putInt(0,count);
        return bytes;
    }
    @Test void validatesBeforeDeliveryAndDeduplicatesWorkgroupDuplicates() {
        var bytes=records(3);
        for(int i=0;i<3;i++)bytes.putInt(16+i*16,7).putInt(20+i*16,-3).putInt(24+i*16,11).putInt(28+i*16,1);
        var delivered=new ArrayList<PackageCollisionCache.Section>();var decoder=new PackageCollisionRequests();
        assertEquals(3,decoder.consume(bytes,delivered::add));
        assertEquals(java.util.List.of(new PackageCollisionCache.Section(7,-3,11)),delivered);
        var invalid=records(2);
        invalid.putInt(16,1).putInt(20,2).putInt(24,3).putInt(28,1);
        invalid.putInt(32,4).putInt(36,5).putInt(40,6).putInt(44,0);
        var callbacks=new ArrayList<PackageCollisionCache.Section>();
        assertThrows(IllegalArgumentException.class,()->decoder.consume(invalid,callbacks::add));
        assertTrue(callbacks.isEmpty(),"malformed tail must not partially queue collision captures");
    }
    @Test void rejectsOverflowedHeadersAndCoordinates() {
        var decoder=new PackageCollisionRequests();var callbacks=new ArrayList<PackageCollisionCache.Section>();
        var negative=records(0);negative.putInt(4,-1);
        assertThrows(IllegalArgumentException.class,()->decoder.consume(negative,callbacks::add));
        var badCoordinate=records(1);badCoordinate.putInt(16,Integer.MAX_VALUE).putInt(20,0).putInt(24,0).putInt(28,1);
        assertThrows(IllegalArgumentException.class,()->decoder.consume(badCoordinate,callbacks::add));
        assertTrue(callbacks.isEmpty());
    }
    @Test void deliversUsageRowsOnlyAfterValidatingTheCompleteSnapshot() {
        var bytes=records(1);long generation=0x123456789abcdef0L;bytes.putLong(8,generation);
        bytes.putInt(16,4).putInt(20,5).putInt(24,6).putInt(28,1);
        bytes.putInt(PackageCollisionRequests.TOUCH_OFFSET,1|(1<<31));
        bytes.putInt(PackageCollisionRequests.TOUCH_OFFSET+8,1<<3);
        bytes.putInt(PackageCollisionRequests.UNSAFE_OFFSET,1<<5);
        bytes.putInt(PackageCollisionRequests.UNSAFE_OFFSET+(PackageCollisionRequests.UNSAFE_WORDS-1)*4,1<<31);
        var decoder=new PackageCollisionRequests();var sections=new ArrayList<PackageCollisionCache.Section>();
        var rows=new ArrayList<Integer>();var versions=new ArrayList<Long>();var unsafeBodies=new ArrayList<Integer>();
        int total=decoder.consume(bytes,sections::add,new PackageCollisionRequests.Usage() {
            @Override public void begin(long version){versions.add(version);}
            @Override public void row(long version,int row){assertEquals(generation,version);rows.add(row);}
            @Override public void unsafeBody(int body){unsafeBodies.add(body);}
        });
        assertEquals(1,total);assertEquals(java.util.List.of(new PackageCollisionCache.Section(4,5,6)),sections);
        assertEquals(java.util.List.of(generation),versions);assertEquals(java.util.List.of(0,31,67),rows);
        assertEquals(java.util.List.of(5,131071),unsafeBodies,"unsafe body indices must retain their GPU slot identity");

        var malformed=records(1);malformed.putInt(16,1).putInt(20,2).putInt(24,3).putInt(28,0);
        assertThrows(IllegalArgumentException.class,()->decoder.consume(malformed,sections::add,new PackageCollisionRequests.Usage() {
            @Override public void begin(long version){fail("malformed result replaced the active package set");}
            @Override public void row(long version,int row){fail("malformed result touched an atlas row");}
            @Override public void unsafeBody(int body){fail("malformed result requested a package handback");}
        }));
    }
}
