package com.iridium126.createmanaindustry.client.particles.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AllayFeatherLifecycleTest {
    private static void set(AllayFeatherGpu owner,String name,Object value) throws Exception {
        var field=AllayFeatherGpu.class.getDeclaredField(name);field.setAccessible(true);field.set(owner,value);
    }
    private static boolean flag(AllayFeatherGpu owner,String name) throws Exception {
        var field=AllayFeatherGpu.class.getDeclaredField(name);field.setAccessible(true);return field.getBoolean(owner);
    }
    @Test void delayedZeroCannotDisableNewFeathers() throws Exception {
        var owner=AllayFeatherGpu.INSTANCE;owner.reset();
        try {
            set(owner,"enabled",true);set(owner,"mayFeathers",true);set(owner,"lastCreationGeneration",100L);
            var snapshot=new ParticleReadbacks.Slot();snapshot.feathers=true;snapshot.generation=99;
            snapshot.data.putInt(32,0).putInt(36,0);owner.observe(snapshot);
            assertTrue(flag(owner,"mayFeathers"));
            snapshot.generation=100;owner.observe(snapshot);
            assertFalse(owner.needsPlayers());assertFalse(flag(owner,"mayFeathers"));
            // No GL context exists in this unit test: any accidental submission fails.
            assertDoesNotThrow(owner::ground);assertDoesNotThrow(()->owner.players(100));assertDoesNotThrow(()->owner.drawMerged(0));
            snapshot.data.putInt(36,0);owner.observe(snapshot);assertEquals(0,owner.committedBuffer());
        } finally {owner.reset();}
    }
    @Test void resetDropsGenerationAndUncommittedEmissionState() throws Exception {
        var owner=AllayFeatherGpu.INSTANCE;
        set(owner,"manual",true);set(owner,"candidateBirth",true);set(owner,"mayFeathers",true);
        set(owner,"spawn",100);set(owner,"remainder",.3);set(owner,"candidateRemainder",.9);set(owner,"lastCreationGeneration",73L);
        owner.reset();assertFalse(owner.creates());assertFalse(owner.enabled());assertFalse(owner.needsPlayers());assertEquals(0,owner.generation());
        assertDoesNotThrow(owner::ground);assertDoesNotThrow(()->owner.players(100));
    }
}
