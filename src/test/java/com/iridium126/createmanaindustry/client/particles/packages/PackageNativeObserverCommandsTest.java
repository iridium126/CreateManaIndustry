package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PackageNativeObserverCommandsTest {
    static ByteBuffer move(int local,long seq){var b=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        PackageNativeObserverPatch.move(b,local,local+1,7,seq,(short)1,(short)0,(short)0,true,false,(byte)0,false,.01f);b.flip();return b;}
    @Test void busyBatchRetainsRelativeOrderAndReleaseAndAllowsNewArrivals() {
        var queue=new PackageNativeObserverCommands(3,8,7);assertTrue(queue.offer(move(0,1)));assertTrue(queue.offer(move(0,2)));
        var seen=new ArrayList<String>();
        assertEquals(0,queue.drain((b,n,s,t)->{assertEquals(1,n);b.position(b.limit());return false;},3,.02f,4));
        assertEquals(2,queue.queued());assertTrue(queue.offer(move(1,1)));
        var release=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());PackageNativeObserverPatch.release(release,0,1,7,3,.01f);release.flip();
        assertTrue(queue.offer(release));
        assertEquals(4,queue.drain((b,n,s,t)->{var destinations=new HashSet<Integer>();for(int i=0;i<n;i++){
            int local=b.getInt(i*64+16);assertTrue(destinations.add(local));seen.add(local+":"+b.getLong(i*64+8)+":"+b.getInt(i*64+24));}return true;},3,.02f,4));
        assertEquals(List.of("0:1:1","0:2:1","1:1:1","0:3:16"),seen);assertEquals(0,queue.queued());
    }
    @Test void exhaustedInboxAndOldEpochDoNotConsumeOrReplaceData() {
        var queue=new PackageNativeObserverCommands(1,2,7);ByteBuffer a=move(0,1),b=move(0,2),c=move(0,3);
        assertTrue(queue.offer(a));assertTrue(queue.offer(b));assertFalse(queue.offer(c));assertEquals(0,c.position());
        var sequences=new ArrayList<Long>();assertEquals(1,queue.drain((bytes,n,s,t)->{sequences.add(bytes.getLong(8));return true;},1,.02f,1));
        assertTrue(queue.offer(c));assertEquals(2,queue.drain((bytes,n,s,t)->{sequences.add(bytes.getLong(8));return true;},1,.02f,2));
        assertEquals(List.of(1L,2L,3L),sequences);c.putLong(0,8).putInt(16,131071);assertFalse(queue.offer(c));assertEquals(0,queue.queued());
    }
    @Test void boundedBaselinePrefixesDoNotBlockReadyMembersOrLoseFutureCommands() {
        var queue=new PackageNativeObserverCommands(3,6,7);assertTrue(queue.offer(move(2,1)));assertTrue(queue.offer(move(0,1)));
        assertTrue(queue.offer(move(2,2)));assertTrue(queue.offer(move(1,1)));var seen=new ArrayList<String>();
        PackageNativeObserverCommands.Submit submit=(b,n,s,t)->{for(int i=0;i<n;i++){int local=b.getInt(i*64+16);assertTrue(local<s);seen.add(local+":"+b.getLong(i*64+8));}return true;};
        assertEquals(0,queue.drain(submit,0,.02f,4));assertEquals(1,queue.drain(submit,1,.02f,4));assertEquals(3,queue.queued());
        assertEquals(1,queue.drain(submit,2,.02f,4));assertEquals(2,queue.drain(submit,3,.02f,4));
        assertEquals(List.of("0:1","1:1","2:1","2:2"),seen);assertEquals(0,queue.queued());
        assertThrows(IllegalArgumentException.class,()->queue.drain(submit,2,.03f,1));
    }
}
