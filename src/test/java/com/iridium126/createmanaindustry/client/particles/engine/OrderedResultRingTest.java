package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.client.particles.engine.spatial.OrderedResultRing;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderedResultRingTest {
    @Test void lateAndDestroyedIdentitiesStillConsumeInOrder(){
        var ring=new OrderedResultRing(3);
        var first=ring.acquire(0,1,0);ring.submit(first);
        var second=ring.acquire(1,1,0);ring.submit(second);
        var third=ring.acquire(2,2,0);ring.submit(third);
        assertNull(ring.acquire(1000,2,0));assertSame(first,ring.head());
        assertThrows(IllegalStateException.class,()->ring.consume(second));
        assertThrows(IllegalStateException.class,()->ring.retire(first));
        for(var ticket:new OrderedResultRing.Ticket[]{first,second,third}){
            ring.consume(ticket);assertThrows(IllegalStateException.class,()->ring.consume(ticket));
        }
        assertEquals(0,ring.pending());assertNull(ring.acquire(1000,2,0));
        ring.retire(second);var next=ring.acquire(1001,3,0);assertEquals(second.slot(),next.slot());
        assertThrows(IllegalStateException.class,()->ring.retire(second));ring.cancel(next);
        ring.retire(first);ring.retire(third);
        assertEquals(3,ring.submitted());assertEquals(3,ring.consumptions());assertEquals(3,ring.retired());assertEquals(0,ring.occupied());
    }
    @Test void exportsCanBeCancelledOnlyBeforeSubmission(){
        var ring=new OrderedResultRing(1);var unused=ring.acquire(1,1,0);ring.cancel(unused);
        var sent=ring.acquire(2,1,0);assertTrue(sent.nonce()>unused.nonce());ring.submit(sent);
        assertThrows(IllegalStateException.class,()->ring.cancel(sent));
        ring.consume(sent);ring.retire(sent);assertEquals(1,ring.consumptions());
    }
}
