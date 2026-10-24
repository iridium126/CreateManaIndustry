package com.iridium126.createmanaindustry.content.allaystorm;

import com.iridium126.createmanaindustry.content.allaystorm.network.StormAttackGatheredPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DomeGatherStateTest {
    static DomeGatherState paused(int flow,float time) { float[] times=new float[66];java.util.Arrays.fill(times,-1);times[flow]=time;return new DomeGatherState(times); }
    @Test void clocksLatchWithoutExcludingAWholeFlow() {
        var p=paused(7,2);assertEquals(1,p.revision());assertEquals(2,p.phaseSeconds(7,20));assertEquals(20,p.phaseSeconds(8,20));
        assertTrue(p.extendsState(DomeGatherState.EMPTY));assertFalse(DomeGatherState.EMPTY.extendsState(p));assertFalse(paused(7,3).extendsState(p));
        var ready=new java.util.BitSet();var eligible=new java.util.BitSet();eligible.set(1,101);ready.set(1,96);
        assertTrue(StormGatherRules.valid(ready,eligible,12288,1,0,2)); // One blocked slot does not remove its flow mates.
        assertThrows(IllegalArgumentException.class,()->paused(0,Float.NaN));assertThrows(IllegalArgumentException.class,()->paused(0,-.5f));
    }
    @Test void snapshotAndFeedbackKeepTheSameClocksAndVersion() {
        var state=paused(65,4.25f);var p=DomePacketTest.packet(2,4,new int[0],new int[]{12288}).withGatherFlows(state);
        var q=DomePacketTest.roundtrip(p);assertEquals(1,q.gatherFlows().revision());assertEquals(4.25f,q.gatherFlows().pausedAt(65));
        assertEquals(4.25f,q.copy(0,4,10,90,new int[12288]).gatherFlows().pausedAt(65));
        var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY,ConnectionType.OTHER);
        try {var feedback=new StormAttackGatheredPacket(Level.OVERWORLD.location(),77,9,new long[]{95},new long[]{127},state);StormAttackGatheredPacket.STREAM_CODEC.encode(b,feedback);var decoded=StormAttackGatheredPacket.STREAM_CODEC.decode(b);assertEquals(77,decoded.epoch());assertEquals(9,decoded.id());assertArrayEquals(feedback.ready(),decoded.ready());assertEquals(4.25f,decoded.flows().pausedAt(65));assertFalse(b.isReadable());}finally{b.release();}
    }
    @Test void invalidOrDuplicateFlowEntriesAreBounded() {
        var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY,ConnectionType.OTHER);
        try {b.writeVarInt(67);assertThrows(io.netty.handler.codec.DecoderException.class,()->DomeGatherState.read(b));b.clear();b.writeVarInt(2);b.writeByte(3);b.writeFloat(1);b.writeByte(3);b.writeFloat(2);assertThrows(io.netty.handler.codec.DecoderException.class,()->DomeGatherState.read(b));}finally{b.release();}
    }
    @Test void pausedFlowKeepsItsSlotsAndLockEntryIsContinuous() {
        int rank=600,flow=DomeTemplate.flowIndex(rank);var p=paused(flow,3.125f);int base=DomeTemplate.ringBase(rank),count=DomeTemplate.ringCount(rank);var channels=new java.util.BitSet();
        for(int r=base;r<base+count;r++) {assertEquals(flow,DomeTemplate.flowIndex(r));assertEquals(DomeTemplate.gatherPoint(r,3.125),DomeTemplate.gatherPoint(r,20,p));assertEquals(0,DomeTemplate.gatherVelocity(r,20,p).length());int mapped=DomeTemplate.mappedRank(r,p.phaseSeconds(flow,20));assertFalse(channels.get(mapped));channels.set(mapped);}
        assertTrue(DomeTemplate.lockedPoint(rank,0,20,p).distanceTo(DomeTemplate.gatherPoint(rank,20,p))<1e-9);
        assertTrue(DomeTemplate.lockedVelocity(rank,0,20,p).length()<1e-9);
        double dt=.00001;assertTrue(DomeTemplate.lockedPoint(rank,dt,20,p).distanceTo(DomeTemplate.lockedPoint(rank,0,20,p))/dt<.01);
    }
}
