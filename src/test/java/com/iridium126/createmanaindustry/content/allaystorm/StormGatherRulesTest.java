package com.iridium126.createmanaindustry.content.allaystorm;
import org.junit.jupiter.api.Test;
import java.util.BitSet;
import static org.junit.jupiter.api.Assertions.*;

class StormGatherRulesTest {
    @Test void thresholdUsesCeilingAndNonzeroEligiblePopulation() {
        assertFalse(StormGatherRules.ready(0,0,1,2));
        assertFalse(StormGatherRules.ready(94,100,1,2)); assertTrue(StormGatherRules.ready(95,100,1,2));
        assertFalse(StormGatherRules.ready(19,21,1,2)); assertTrue(StormGatherRules.ready(20,21,1,2));
        assertFalse(StormGatherRules.ready(3891,4096,1,2)); assertTrue(StormGatherRules.ready(3892,4096,1,2));
        assertFalse(StormGatherRules.ready(11673,12288,1,2)); assertTrue(StormGatherRules.ready(11674,12288,1,2));
        assertFalse(StormGatherRules.ready(101,100,1,2));
    }
    @Test void bitmapBoundsAndNonemptyDenominatorAreValidated() {
        var eligible=new BitSet(); eligible.set(0,100);
        var ready=new BitSet(); ready.set(0,95);
        assertTrue(StormGatherRules.valid(ready,eligible,12288,1,0,2));
        ready.set(110); assertFalse(StormGatherRules.valid(ready,eligible,12288,1,0,2));
        ready.clear(110); eligible.set(12288); assertFalse(StormGatherRules.valid(ready,eligible,12288,1,0,2));
        assertFalse(StormGatherRules.valid(new BitSet(),new BitSet(),12288,1,0,2));
    }
    @Test void gatherOrbitUsesThreeMetreTangentialSpeed() {
        for(int rank=0;rank<64;rank++) {
            var origin=net.minecraft.world.phys.Vec3.ZERO;
            double dt=.00001;
            var p=StarfallColumnRules.gather(origin,rank,37,2);
            var q=StarfallColumnRules.gather(origin,rank,37,2+dt);
            assertEquals(3,p.distanceTo(q)/dt,.00001);
        }
    }
}
