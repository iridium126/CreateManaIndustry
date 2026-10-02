package com.iridium126.createmanaindustry.client.particles.packages;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class PackageSimulationClockTest {
    @Test void fiveThroughSixtyFpsRetainEveryStep(){
        for(int hz:new int[]{5,9,10,15,20,30,60}){
            var clock=new PackageSimulationClock();int steps=0;
            for(int frame=0;frame<=hz*10;frame++){
                long now=(long)frame*1_000_000_000L/hz,tick=now/50_000_000L;
                clock.sample(now,tick,false);int submitted=0;
                while(submitted<4&&clock.due(tick)){long next=clock.nextTick();clock.commit(next);submitted++;steps++;}
                assertFalse(clock.historyGap(),"FPS "+hz);assertTrue(submitted<=4);
            }assertEquals(200,steps,"FPS "+hz);
        }
    }
    @Test void delayedWorkerRetainsTimeAndExactInputTicks(){
        var clock=new PackageSimulationClock();clock.sample(0,10,false);
        clock.sample(200_000_000,14,false);assertEquals(11,clock.nextTick());assertTrue(clock.due(14));assertEquals(0,clock.step());
        // No commit while an input or geometry bank is unavailable.
        clock.sample(250_000_000,15,false);assertEquals(11,clock.nextTick());
        for(long tick=11;tick<=14;tick++)clock.commit(tick);
        assertEquals(15,clock.nextTick());assertTrue(clock.due(15));clock.commit(15);assertEquals(5,clock.step());
    }
    @Test void movingPosePairCannotBeConsumedTwice(){
        var clock=new PackageSimulationClock();clock.sample(0,10,false);clock.sample(100_000_000,12,false);
        clock.commit(11);assertThrows(IllegalStateException.class,()->clock.commit(11));clock.commit(12);
        assertFalse(clock.due(12));
    }
    @Test void historyGapRequiresExplicitBaselineAndDoesNotInventSteps(){
        var clock=new PackageSimulationClock();clock.sample(0,1,false);clock.sample(1_050_000_000,22,false);
        assertTrue(clock.historyGap());assertFalse(clock.due(22));assertEquals(0,clock.step());
        clock.rebase(1_050_000_000,22);clock.sample(1_100_000_000,23,false);clock.commit(23);assertEquals(1,clock.step());
    }
    @Test void pauseAndWrapAreSafe(){
        var clock=new PackageSimulationClock();long now=Long.MAX_VALUE-10_000_000;
        clock.sample(now,1,false);clock.sample(now+50_000_000,2,false);clock.commit(2);
        clock.sample(now+9_000_000_000L,2,true);assertEquals(0,clock.interpolation());
        clock.sample(now+9_050_000_000L,3,false);clock.commit(3);assertEquals(2,clock.step());
    }
}
