package com.iridium126.createmanaindustry.content.allaystorm;

import org.junit.jupiter.api.Test;
import java.util.BitSet;
import static org.junit.jupiter.api.Assertions.*;

class DomeGatherRulesTest {
    @Test void thresholdFallsFromAllToNinetyPercentWithIntegerCeilings(){
        assertEquals(12288,DomeGatherRules.required(12288,0,1000));
        assertEquals(11674,DomeGatherRules.required(12288,500,1000));
        assertEquals(11060,DomeGatherRules.required(12288,1000,1000));
        for(int eligible:new int[]{0,1,21,32,4096,12288})for(int duration:new int[]{1,160,627,12000}){
            int previous=eligible;
            for(int elapsed=0;elapsed<=duration;elapsed++){
                int required=DomeGatherRules.required(eligible,elapsed,duration);
                assertTrue(required<=previous);assertEquals(eligible>0,DomeGatherRules.ready(required,eligible,elapsed,duration));
                if(required>0)assertFalse(DomeGatherRules.ready(required-1,eligible,elapsed,duration));previous=required;
            }
        }
        assertEquals(12288,DomeGatherRules.required(12288,-1,100));assertEquals(11060,DomeGatherRules.required(12288,101,100));
        assertFalse(DomeGatherRules.ready(12289,12288,100,100));assertFalse(DomeGatherRules.ready(0,0,100,100));
    }
    @Test void deadlineIncludesItsLastTickAndWrapsTheSharedClock(){
        for(float start:new float[]{0,5000,104850}){
            float deadline=start+31.35f,wrapped=deadline>=104857.6f?deadline-104857.6f:deadline;
            assertEquals(627,DomeGatherRules.durationTicks(start,deadline));assertTrue(DomeGatherRules.withinDeadline(wrapped,start,deadline));
            assertFalse(DomeGatherRules.withinDeadline(wrapped+.05f,start,deadline));assertFalse(DomeGatherRules.withinDeadline(start-.05f,start,deadline));
        }
    }
    @Test void masksRemainExactAndHelixKeepsItsFixedRule(){
        var eligible=new BitSet();eligible.set(0,12288);var ready=new BitSet();ready.set(0,11060);
        assertFalse(DomeGatherRules.valid(ready,eligible,12288,0,0,30));assertTrue(DomeGatherRules.valid(ready,eligible,12288,30,0,30));
        assertFalse(StormGatherRules.valid(ready,eligible,12288,1,0,2));ready.set(12288);assertFalse(DomeGatherRules.valid(ready,eligible,12288,30,0,30));
        ready.clear(12288);eligible.clear(0);assertFalse(DomeGatherRules.valid(ready,eligible,12288,30,0,30));
    }
}
