package com.iridium126.createmanaindustry.client.particles.engine;

import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AllayFeatherLifecycleTest {
    private static void set(AllayFeatherGpu owner,String name,Object value) throws Exception {
        var field=AllayFeatherGpu.class.getDeclaredField(name);field.setAccessible(true);field.set(owner,value);
    }
    private static boolean flag(AllayFeatherGpu owner,String name) throws Exception {
        var field=AllayFeatherGpu.class.getDeclaredField(name);field.setAccessible(true);return field.getBoolean(owner);
    }
    @Test void automaticEmissionUsesTheCurrentHorizontalRadius() throws Exception {
        var owner=AllayFeatherGpu.INSTANCE;owner.reset();
        int featherRate=ClientConfig.allayFeatherMaxSpawnPerSecond,goldRate=ClientConfig.allayGoldMaxSpawnPerSecond;
        ClientConfig.allayFeatherMaxSpawnPerSecond=1000;ClientConfig.allayGoldMaxSpawnPerSecond=2000;
        var center=new Vec3(-1000,192,2000);
        try {
            assertTrue(owner.prepareAutomatic(center.add(0,-500,63),center,32,.05f,1));
            assertTrue(flag(owner,"candidateBirth"));assertTrue(flag(owner,"candidateGoldBirth"));
            assertTrue(owner.prepareAutomatic(center.add(0,500,64),center,32,.05f,1));
            assertFalse(owner.prepareAutomatic(center.add(0,0,64.001),center,32,.05f,1));
            assertFalse(owner.creates());
            assertTrue(owner.prepareAutomatic(center.add(0,0,64.001),center,33,.05f,1));
            assertFalse(owner.prepareAutomatic(null,center,32,.05f,1));
            assertFalse(owner.prepareAutomatic(center,null,32,.05f,1));
            assertFalse(owner.prepareAutomatic(center,center,0,.05f,1));

            // Leaving the radius discards fractional automatic work.
            set(owner,"remainder",.99);set(owner,"goldRemainder",.99);
            assertFalse(owner.prepareAutomatic(center.add(65,0,0),center,32,.05f,1));
            owner.idle();
            assertTrue(owner.prepareAutomatic(center,center,32,.0001f,1));
            assertFalse(owner.creates());

            owner.commit(1);
            set(owner,"enabled",true);set(owner,"mayFeathers",true);set(owner,"mayGold",true);
            assertFalse(owner.prepareAutomatic(null,null,0,.05f,1));
            assertTrue(owner.needsPlayers());assertTrue(flag(owner,"mayGold"));
            set(owner,"manual",true);set(owner,"manualGold",true);
            assertFalse(owner.prepareAutomatic(null,null,0,.05f,1));
            assertTrue(owner.creates());assertTrue(flag(owner,"candidateBirth"));assertTrue(flag(owner,"candidateGoldBirth"));
        } finally {
            owner.reset();
            ClientConfig.allayFeatherMaxSpawnPerSecond=featherRate;ClientConfig.allayGoldMaxSpawnPerSecond=goldRate;
        }
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
    @Test void goldOnlyTailNeverSubmitsGroundOrOpaqueDraws() throws Exception {
        var owner=AllayFeatherGpu.INSTANCE;owner.reset();
        try{
            set(owner,"enabled",true);set(owner,"mayGold",true);set(owner,"lastGoldCreationGeneration",101L);
            var snapshot=new ParticleReadbacks.Slot();snapshot.feathers=true;snapshot.generation=100;
            snapshot.data.putInt(32,0).putInt(36,0);owner.observe(snapshot);assertTrue(flag(owner,"mayGold"));
            snapshot.generation=101;snapshot.data.putInt(36,12);owner.observe(snapshot);assertTrue(flag(owner,"mayGold"));
            assertFalse(owner.needsPlayers());assertFalse(owner.requestFrame());assertEquals(0,owner.committedBuffer());
            assertDoesNotThrow(owner::ground);assertDoesNotThrow(()->owner.players(100));assertDoesNotThrow(()->owner.drawMerged(0));
            snapshot.data.putInt(36,0);owner.observe(snapshot);assertFalse(flag(owner,"mayGold"));
            set(owner,"manualGold",true);set(owner,"candidateGoldBirth",true);owner.reset();assertFalse(owner.creates());
        }finally{owner.reset();}
    }
    @Test void resetDropsGenerationAndUncommittedEmissionState() throws Exception {
        var owner=AllayFeatherGpu.INSTANCE;
        set(owner,"manual",true);set(owner,"candidateBirth",true);set(owner,"mayFeathers",true);
        set(owner,"spawn",100);set(owner,"remainder",.3);set(owner,"candidateRemainder",.9);set(owner,"lastCreationGeneration",73L);
        owner.reset();assertFalse(owner.creates());assertFalse(owner.enabled());assertFalse(owner.needsPlayers());assertEquals(0,owner.generation());
        assertDoesNotThrow(owner::ground);assertDoesNotThrow(()->owner.players(100));
    }
}
