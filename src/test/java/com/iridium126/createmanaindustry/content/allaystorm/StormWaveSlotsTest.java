package com.iridium126.createmanaindustry.content.allaystorm;

import com.iridium126.createmanaindustry.content.allaystorm.network.ClientboundStormWavePacket;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StormWaveSlotsTest {
    private ClientboundStormWavePacket start(int id,float end) {
        return new ClientboundStormWavePacket(id,87,0,123,5,5,1,512,0,4,10,end,1,12,2,4,
                new int[]{id*10,id*10+1},new float[]{1,12,2,3,10,4},new float[]{3,.5f,4,3.5f});
    }
    private StormWaveSlots full() {
        var slots=new StormWaveSlots();
        for(int id=1;id<=4;id++)assertEquals(id-1,slots.apply(start(id,50),0).slot());
        return slots;
    }
    @Test void sameFrameAbortStartUpdateLockReplacesFadingSlot() {
        var slots=full(); slots.apply(ClientboundStormWavePacket.abort(2,87),5);
        var replacement=slots.apply(start(5,50),5);
        assertNotNull(replacement);assertTrue(replacement.replacement());assertEquals(1,replacement.slot());
        var update=start(5,50).copy(1,2,12,50,new int[0]);
        assertArrayEquals(new int[]{50,51},slots.apply(update,5).packet().members());
        assertTrue(slots.apply(update.copy(2,3,12,50,new int[0]),5).packet().locked());
        assertNull(slots.apply(update,5));
        assertNull(slots.apply(ClientboundStormWavePacket.abort(2,87),5));
        assertEquals(5,slots.packet(1).waveId());
    }
    @Test void liveSlotsCannotBeEvicted() {assertNull(full().apply(start(5,50),5));}
    @Test void emptySlotPreservesAbortFade() {
        var slots=new StormWaveSlots(); slots.apply(start(1,50),0); slots.apply(ClientboundStormWavePacket.abort(1,87),5);
        assertEquals(1,slots.apply(start(2,50),5).slot());assertTrue(slots.packet(0).abort());
    }
    @Test void expiredSlotsBeforeAbortAndAbortsBySlotIndex() {
        var slots=full();slots.apply(ClientboundStormWavePacket.abort(3,87),5);slots.apply(ClientboundStormWavePacket.abort(2,87),5);
        assertEquals(1,slots.apply(start(5,50),5).slot());
        slots=full();slots.apply(ClientboundStormWavePacket.abort(1,87),5);
        assertEquals(0,slots.apply(start(6,50),5.3f).slot());
    }
}
