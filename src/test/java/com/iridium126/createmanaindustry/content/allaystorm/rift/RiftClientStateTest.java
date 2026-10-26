package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.client.allaystorm.rift.RiftClientState;
import com.iridium126.createmanaindustry.content.allaystorm.StormIdentity;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RiftClientStateTest {
    StormRiftSnapshot snapshot(long epoch,long sequence,int state) {
        return new StormRiftSnapshot(new StormIdentity(ResourceLocation.withDefaultNamespace("overworld"),epoch),sequence,30_000_000.125,-29_999_000.25,384,16384,10,100,state,AllaySkyContext.EMPTY);
    }
    @Test void stopTombstoneRejectsLateUpdatesAndResubscriptionRequiresNewSequence() {
        var state=new RiftClientState();assertTrue(state.accept(snapshot(1,3,StormRiftSnapshot.OPEN)));
        assertTrue(state.accept(snapshot(1,4,StormRiftSnapshot.REMOVE)));
        assertFalse(state.accept(snapshot(1,3,StormRiftSnapshot.OPEN)));
        assertFalse(state.accept(snapshot(1,4,StormRiftSnapshot.OPEN)));
        assertTrue(state.accept(snapshot(1,5,StormRiftSnapshot.OPEN)));
        assertEquals(30_000_000.125,state.current().x());
    }
    @Test void replacementRetiresTheOldEpochUntilWorldIsCleared() {
        var state=new RiftClientState();assertTrue(state.accept(snapshot(1,50,StormRiftSnapshot.OPEN)));
        assertTrue(state.accept(snapshot(2,1,StormRiftSnapshot.OPEN)));
        assertFalse(state.accept(snapshot(1,51,StormRiftSnapshot.OPEN)));
        state.clear();assertTrue(state.accept(snapshot(1,1,StormRiftSnapshot.OPEN)));
    }
}
