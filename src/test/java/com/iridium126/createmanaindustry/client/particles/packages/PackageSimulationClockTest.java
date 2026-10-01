package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PackageSimulationClockTest {
    @Test void twentyHertzDoesNotDependOnRenderFrequency() {
        for(int hz:new int[]{30,60,144,240}) {
            var clock=new PackageSimulationClock();int steps=0;
            for(int frame=0;frame<=hz;frame++) {
                long now=(long)frame*1_000_000_000L/hz;
                var advance=clock.advance(now,now/50_000_000L,false);
                assertNotEquals(PackageSimulationClock.Advance.OVERDUE,advance);
                if(advance==PackageSimulationClock.Advance.STEP)steps++;
            }
            assertEquals(20,steps,"render Hz "+hz);
        }
    }
    @Test void oneMovingPosePairCannotBeAppliedTwice() {
        var clock=new PackageSimulationClock();clock.advance(0,10,false);
        assertEquals(PackageSimulationClock.Advance.STEP,clock.advance(50_000_000,11,false));
        assertEquals(PackageSimulationClock.Advance.IDLE,clock.advance(100_000_000,11,false));
        assertEquals(PackageSimulationClock.Advance.STEP,clock.advance(101_000_000,12,false));
        assertEquals(.02f,clock.interpolation(),1e-6f);
    }
    @Test void longFrameAndRepeatedFrozenTickRequireHandback() {
        var clock=new PackageSimulationClock();clock.advance(0,1,false);
        assertEquals(PackageSimulationClock.Advance.OVERDUE,clock.advance(100_000_001,2,false));
        clock.reset();clock.advance(0,1,false);
        assertEquals(PackageSimulationClock.Advance.IDLE,clock.advance(100_000_000,1,false));
        assertEquals(PackageSimulationClock.Advance.OVERDUE,clock.advance(100_000_001,1,false));
    }
    @Test void pauseAndWorldResetDiscardElapsedTime() {
        var clock=new PackageSimulationClock();clock.advance(0,1,false);
        assertEquals(PackageSimulationClock.Advance.IDLE,clock.advance(9_000_000_000L,1,true));
        assertEquals(0,clock.interpolation());
        assertEquals(PackageSimulationClock.Advance.STEP,clock.advance(9_050_000_000L,2,false));
        clock.reset();assertEquals(PackageSimulationClock.Advance.IDLE,clock.advance(99_000_000_000L,1,false));
    }
    @Test void backwardsClockRequiresHandback() {
        var clock=new PackageSimulationClock();clock.advance(20,1,false);
        assertEquals(PackageSimulationClock.Advance.OVERDUE,clock.advance(19,2,false));
    }
    @Test void nanoTimeOriginAndWrapAreNotAbsoluteTime() {
        var clock=new PackageSimulationClock();clock.advance(-100_000_000,1,false);
        assertEquals(PackageSimulationClock.Advance.STEP,clock.advance(-50_000_000,2,false));
        clock.reset();long before=Long.MAX_VALUE-10_000_000;clock.advance(before,1,false);
        assertEquals(PackageSimulationClock.Advance.STEP,clock.advance(before+50_000_000,2,false));
    }
}
