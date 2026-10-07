package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageFreezeReportTest {
    private ByteBuffer snapshot(int changed,int frozen){
        var bytes=ByteBuffer.allocate(PackageFreezeReport.BYTES).order(ByteOrder.nativeOrder());
        bytes.putInt(0,changed).putInt(4,frozen);return bytes;
    }
    @Test void decodesExactLifecycleStepAndWorldContextFromBorrowedSnapshot(){
        var bytes=snapshot(1,1);bytes.putInt(16+2*4,1);int p=PackageFreezeReport.EVENT_OFFSET;
        bytes.putInt(p,0x80000000|(7<<16)|2).putLong(p+8,(1L<<34)+7);
        bytes.putInt(p+16,-10).putInt(p+20,2000000).putInt(p+24,4);
        bytes.putFloat(p+32,8).putFloat(p+36,16).putFloat(p+40,-8).putFloat(p+44,.375f);
        bytes.putLong(p+64,(1L<<33)+1).putLong(p+72,(1L<<32)+2).putLong(p+80,(1L<<35)+3);
        bytes.putInt(p+96,12).putInt(p+100,44).putLong(p+104,(1L<<36)+4).putInt(p+112,1);
        var events=new ArrayList<PackageFreezeReport.Event>();var summary=PackageFreezeReport.decode(bytes,events::add);
        var event=events.getFirst();assertEquals(PackageFreezeReport.Reason.SECTION_MISSING,event.reason());
        assertEquals("resume",event.stage());assertTrue(event.stillFrozen());assertEquals((1L<<34)+7,event.step());
        assertEquals((1L<<33)+1,event.id());assertEquals((1L<<32)+2,event.generation());assertEquals((1L<<35)+3,event.lease());
        assertEquals((1L<<36)+4,event.revision());assertEquals(43,event.index());assertEquals(12,event.body());
        assertEquals("section=-10,2000000,4",event.context());assertEquals(1,summary.frozen());
        bytes.putLong(p+64,777);assertEquals((1L<<33)+1,event.id(),"recycled scratch must not rewrite a previous lifecycle");
        int[] counts=summary.reasons();counts[2]=0;assertEquals(1,summary.reasons()[2]);
    }
    @Test void geometryDiagnosticsPreserveExactRevisionAndWaitPhase(){
        var bytes=snapshot(1,1);bytes.putInt(16+8*4,1);int p=PackageFreezeReport.EVENT_OFFSET;
        bytes.putInt(p,(4<<16)|8).putInt(p+16,4).putInt(p+20,-1).putInt(p+24,2).putInt(p+28,3);
        var events=new ArrayList<PackageFreezeReport.Event>();PackageFreezeReport.decode(bytes,events::add);
        assertEquals("movingId=4 geometryRevision=12884901887 geometryWait=upload",events.getFirst().context());
    }
    @Test void worldVersionFailurePreservesFullRevisionAndPhysicalStage(){
        var bytes=snapshot(1,1);bytes.putInt(16+19*4,1);int p=PackageFreezeReport.EVENT_OFFSET;
        bytes.putInt(p,0x80000000|(2<<24)|(7<<16)|19).putInt(p+16,-1).putInt(p+20,4).putInt(p+24,0).putInt(p+28,2).putInt(p+60,-1);
        var events=new ArrayList<PackageFreezeReport.Event>();PackageFreezeReport.decode(bytes,events::add);var event=events.getFirst();
        assertEquals("resume",event.stage());assertEquals(12884901887L,event.requiredWorldRevision());
        assertEquals("section=-1,4,0 requiredRevision=12884901887 worldWait=upload",event.context());
    }
    @Test void recoveredBeforeReadbackCanStillReportItsFailure(){
        var bytes=snapshot(1,0);int p=PackageFreezeReport.EVENT_OFFSET;
        bytes.putInt(p,(6<<16)|18).putInt(p+28,Float.floatToIntBits(.4f));
        var events=new ArrayList<PackageFreezeReport.Event>();var summary=PackageFreezeReport.decode(bytes,events::add);
        assertEquals(0,summary.frozen());assertFalse(events.getFirst().stillFrozen());
        assertEquals(PackageFreezeReport.Reason.ENV_HEALTH,events.getFirst().reason());assertTrue(events.getFirst().context().contains("health=0.4"));
    }
    @Test void outputOverflowRemainsExplicitAndBounded(){
        int total=PackageFreezeReport.MAX_EVENTS+3;var bytes=snapshot(total,total);bytes.putInt(16+2*4,total);
        for(int i=0;i<PackageFreezeReport.MAX_EVENTS;i++)bytes.putInt(PackageFreezeReport.EVENT_OFFSET+i*PackageFreezeReport.EVENT_BYTES,2);
        var events=new ArrayList<PackageFreezeReport.Event>();var summary=PackageFreezeReport.decode(bytes,events::add);
        assertEquals(PackageFreezeReport.MAX_EVENTS,events.size());assertEquals(3,summary.deferred());assertEquals(total,summary.frozen());
    }
    @Test void corruptHeaderCannotDeliverAnUnvalidatedPrefix(){
        var bytes=snapshot(1,1);var events=new ArrayList<PackageFreezeReport.Event>();
        assertThrows(IllegalArgumentException.class,()->PackageFreezeReport.decode(bytes,events::add));assertTrue(events.isEmpty());
        assertThrows(IllegalArgumentException.class,()->PackageFreezeReport.decode(ByteBuffer.allocate(1),events::add));
    }
}
