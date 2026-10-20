package com.iridium126.createmanaindustry.content.allaystorm;
import org.junit.jupiter.api.Test;
import java.util.BitSet;
import static org.junit.jupiter.api.Assertions.*;

class StormGatherRulesTest {
    @Test void thresholdUsesCeilingAndNonzeroEligiblePopulation() {
        assertFalse(StormGatherRules.ready(0,0));
        assertFalse(StormGatherRules.ready(94,100)); assertTrue(StormGatherRules.ready(95,100));
        assertFalse(StormGatherRules.ready(19,21)); assertTrue(StormGatherRules.ready(20,21));
        assertFalse(StormGatherRules.ready(3891,4096)); assertTrue(StormGatherRules.ready(3892,4096));
        assertFalse(StormGatherRules.ready(11673,12288)); assertTrue(StormGatherRules.ready(11674,12288));
        assertFalse(StormGatherRules.ready(101,100));
    }
    @Test void BlockedAndUnknownAreExcludedButCannotBeReady() {
        var eligible=new BitSet(); eligible.set(0,100);
        var ready=new BitSet(); ready.set(0,95);
        assertTrue(StormGatherRules.valid(ready,eligible,12288));
        ready.set(110); assertFalse(StormGatherRules.valid(ready,eligible,12288));
        ready.clear(110); eligible.set(12288); assertFalse(StormGatherRules.valid(ready,eligible,12288));
        assertFalse(StormGatherRules.valid(new BitSet(),new BitSet(),12288));
    }
    @Test void gatherOrbitMatchesSixMetreDescentEntry() {
        for(int rank=0;rank<64;rank++) {
            var origin=net.minecraft.world.phys.Vec3.ZERO;
            double dt=.00001;
            var p=StarfallColumnRules.gather(origin,rank,37,2);
            var q=StarfallColumnRules.gather(origin,rank,37,2+dt);
            assertEquals(6,p.distanceTo(q)/dt,.00001);
        }
    }
}
