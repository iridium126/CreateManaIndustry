package com.iridium126.createmanaindustry.content.allaystorm;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AllayStormPersistenceTest {
    @Test void initialMatureStormIsOpenWithoutReloadAndKeepsItsFirstOpenTime(){
        for(int count:new int[]{16383,16384,20000}){
            var d=AllayStormData.create(BlockPos.ZERO,count,1234,17);
            assertEquals(count>=16384?1234:-1,d.riftOpenedAt);
            var restored=serializer.readAt(serializer.write(d,null),2000);
            assertEquals(d.riftOpenedAt,restored.riftOpenedAt);
        }
    }
    @Test void matureAndNoGrowthPhasesAdvanceInsideEachTick(){
        for(float growth:new float[]{0,20}){
            var a=AllayStormPhases.evaluate(16,16,growth,100,200,.1f,16384,18);
            var b=AllayStormPhases.evaluate(16,16,growth,100,200,.9f,16384,18);
            assertTrue(b.rotation()>a.rotation());assertTrue(b.conveyor()>a.conveyor());
            assertEquals(AllayStormPhases.evaluate(16,16,growth,100,200,16384,18),
                    AllayStormPhases.evaluate(16,16,growth,100,200,0,16384,18));
        }
    }
    private final AllayStormData.Serializer serializer = new AllayStormData.Serializer();
    private AllayStormData storm(int count, double rate) {
        var d = new AllayStormData();
        d.active = true; d.anchor = new BlockPos(4,128,-8); d.count = count; d.stormSeed = 123;
        d.creationRadius = 8; d.finalCount = 65536; d.growthPerSecond = rate;
        d.finalRadius = rate > 0 ? 32 : 8; d.createdAtGameTime = 100;
        d.dead.set(2); d.hp.put(3, 7.5f);
        return d;
    }
    private AllayStormPhases phase(AllayStormData d, long now) {
        return AllayStormPhases.evaluate(d.creationRadius, d.finalRadius, (float)d.growthPerSecond,
                d.createdAtGameTime, now, d.count, d.stormSeed);
    }
    @Test void growingStormRoundTripsFrozenLawAndLives() {
        var d = storm(16384,20); var restored = serializer.readAt(serializer.write(d,null), 20000);
        assertEquals(8, restored.creationRadius); assertEquals(32, restored.finalRadius);
        assertEquals(d.anchor, restored.anchor); assertEquals(d.dead, restored.dead); assertEquals(d.hp, restored.hp);
        assertEquals(phase(d,20000),phase(restored,20000));
    }
    @Test void fullyGrownStormKeepsOriginalGrowthWindow() {
        var d = storm(65536,20); var restored = serializer.readAt(serializer.write(d,null), 100000);
        assertEquals(20,restored.growthPerSecond); assertEquals(8,restored.creationRadius);
        assertEquals(100,restored.createdAtGameTime); assertEquals(phase(d,100000),phase(restored,100000));
    }
    @Test void zeroGrowthKeepsFrozenCeilingAndAge() {
        var d = storm(4096,0); var restored = serializer.readAt(serializer.write(d,null), 20000);
        assertEquals(d.finalCount, restored.finalCount); assertEquals(8,restored.finalRadius);
        assertEquals(phase(d,20000),phase(restored,20000));
    }
    @Test void invalidOrMissingLawRebasesWithoutLosingLives() {
        for (int mode=0;mode<5;mode++) {
            CompoundTag tag=serializer.write(storm(16384,20),null);
            switch(mode) {
                case 0 -> tag.remove("CreationRadius");
                case 1 -> tag.putFloat("CreationRadius",Float.NaN);
                case 2 -> tag.putDouble("GrowthRate",Double.POSITIVE_INFINITY);
                case 3 -> tag.putLong("CreatedAt",Long.MAX_VALUE);
                case 4 -> tag.putDouble("GrowthRate",1e300);
            }
            var restored=serializer.readAt(tag,20000);
            assertEquals(0,restored.growthPerSecond); assertEquals(16384,restored.finalCount);
            assertEquals(20000,restored.createdAtGameTime); assertTrue(restored.dead.get(2)); assertEquals(7.5f,restored.hpOf(3));
            var phases=phase(restored,20000); assertEquals(0,phases.rotation()); assertEquals(0,phases.conveyor());
            assertTrue(Float.isFinite(phase(restored,21000).rotation()));
        }
    }
}
