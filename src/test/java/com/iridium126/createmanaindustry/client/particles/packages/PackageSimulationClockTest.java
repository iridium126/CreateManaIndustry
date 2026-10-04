package com.iridium126.createmanaindustry.client.particles.packages;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class PackageSimulationClockTest {
    @Test void blockedFreeClockDoesNotConsumeOrBlockTheChainClock(){
        var free=new PackageSimulationClock();var chain=new PackageSimulationClock();
        free.sample(0,0,false);chain.sample(0,0,false);
        for(int tick=1;tick<=10;tick++){
            free.sample(tick*50_000_000L,tick,false);chain.sample(tick*50_000_000L,tick,false);
            while(chain.due(tick))chain.commit(chain.nextTick());
        }
        assertEquals(0,free.step());assertEquals(10,chain.step());assertEquals(1,free.nextTick());
        for(int i=0;i<free.stepsPerFrame()&&free.due(10);i++)free.commit(free.nextTick());
        assertEquals(4,free.step());assertEquals(10,chain.step());
    }
    @Test void twoHundredTicksPerSecondConsumeEveryInputWithoutRevocation(){
        var clock=new PackageSimulationClock();int submitted=0;
        for(int frame=0;frame<=600;frame++){
            long now=frame*1_000_000_000L/60,tick=now*200/1_000_000_000L;
            clock.sample(now,tick,false,200);
            for(int n=0;n<clock.stepsPerFrame()&&clock.due(tick);n++){clock.commit(clock.nextTick());submitted++;}
            assertFalse(clock.historyGap(),"200 tick/s incorrectly expired input history at frame "+frame);
        }
        assertEquals(2000,submitted);
    }
    @Test void highTickRateKeepsOneSecondHistoryAndCatchupAtFiveFps(){
        for(int fps:new int[]{5,30,60,200}){
            var clock=new PackageSimulationClock();int steps=0;
            for(int frame=0;frame<=fps*10;frame++){
                long now=frame*1_000_000_000L/fps,tick=now*200/1_000_000_000L;
                clock.sample(now,tick,false,200);int submitted=0;
                while(submitted<clock.stepsPerFrame()&&clock.due(tick)){clock.commit(clock.nextTick());submitted++;steps++;}
                assertFalse(clock.historyGap());assertTrue(submitted<=40);
            }
            assertEquals(2000,steps,"200 tick/s at FPS "+fps);
        }
        var clock=new PackageSimulationClock();clock.sample(0,0,false,200);
        clock.sample(500_000_000,100,false,200);assertFalse(clock.historyGap());assertEquals(1,clock.nextTick());
        clock.sample(1_005_000_000,201,false,200);assertTrue(clock.historyGap());assertEquals(0,clock.step());
    }
    @Test void changingTickRatePreservesStepIdentityAndFractionalReservation(){
        var clock=new PackageSimulationClock();clock.sample(0,0,false,20);
        clock.sample(25_000_000,0,false,20);assertEquals(.5f,clock.interpolation());
        clock.sample(25_000_000,0,false,200);assertEquals(.5f,clock.interpolation());
        clock.sample(27_500_000,1,false,200);clock.commit(1);assertEquals(1,clock.step());
        clock.sample(27_500_000,1,false,20);clock.sample(77_500_000,2,false,20);clock.commit(2);assertEquals(2,clock.step());
        assertFalse(clock.historyGap());
    }
    @Test void slowServerDoesNotTurnWallTimeIntoMissingHistory(){
        for(int tps:new int[]{5,10,15}){
            var clock=new PackageSimulationClock();int submitted=0;
            for(int frame=0;frame<=60*20;frame++){
                long now=frame*1_000_000_000L/60,tick=now*tps/1_000_000_000L;
                clock.sample(now,tick,false);
                for(int n=0;n<4&&clock.due(tick);n++){clock.commit(clock.nextTick());submitted++;}
                assertFalse(clock.historyGap(),"healthy input history at server TPS "+tps+", frame "+frame);
            }
            assertEquals(tps*20,submitted);
        }
    }
    @Test void stalledServerRetainsOneFutureStepAndResumesWithoutInventingTicks(){
        var clock=new PackageSimulationClock();clock.sample(0,10,false);
        clock.sample(5_000_000_000L,10,false);
        assertFalse(clock.historyGap());assertFalse(clock.due(10));assertEquals(0,clock.step());
        clock.sample(5_000_000_001L,11,false);assertTrue(clock.due(11));clock.commit(11);
        assertFalse(clock.due(11));assertEquals(1,clock.step());
    }
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
    @Test void longRenderStallCanRebaselineWithoutDroppingGpuAuthority(){
        var clock=new PackageSimulationClock();clock.sample(0,10,false);clock.sample(50_000_000L,11,false);clock.commit(11);
        clock.sample(1_100_000_000L,33,false);
        assertTrue(clock.historyGap());assertEquals(1,clock.step());
        clock.rebase(1_100_000_000L,33);
        clock.sample(1_150_000_000L,34,false);
        assertTrue(clock.due(34));clock.commit(34);
        assertEquals(2,clock.step());assertFalse(clock.historyGap());assertFalse(clock.due(34));
    }
    @Test void pauseAndWrapAreSafe(){
        var clock=new PackageSimulationClock();long now=Long.MAX_VALUE-10_000_000;
        clock.sample(now,1,false);clock.sample(now+50_000_000,2,false);clock.commit(2);
        clock.sample(now+9_000_000_000L,2,true);assertEquals(0,clock.interpolation());
        clock.sample(now+9_050_000_000L,3,false);clock.commit(3);assertEquals(2,clock.step());
    }
}
