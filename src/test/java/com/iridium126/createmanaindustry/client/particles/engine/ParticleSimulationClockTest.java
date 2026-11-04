package com.iridium126.createmanaindustry.client.particles.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParticleSimulationClockTest {
    @Test void displayFrequencyDoesNotChangeSimulationTicks() {
        for (int fps : new int[]{30, 60, 144, 240}) {
            var clock = new ParticleSimulationClock();
            var world = new Object();
            int steps = 0;
            for (int frame = 0; frame < fps * 10; frame++) {
                double time = (double) frame * 20 / fps;
                clock.beginFrame(world, (long) time, (float) (time - Math.floor(time)), false);
                while (clock.hasStep()) { clock.commit(clock.nextTick()); steps++; }
            }
            assertEquals(200, steps, "FPS " + fps);
            assertEquals(.05f, ParticleSimulationClock.STEP_SECONDS);
        }
    }
    @Test void hitchHasBoundedCatchupWithoutLosingTicks() {
        var c = new ParticleSimulationClock(); var world = new Object();
        c.beginFrame(world, 0, 0, false); c.commit(0);
        c.beginFrame(world, 10, .4f, false);
        c.commit(1); c.commit(2);
        assertFalse(c.hasStep()); assertEquals(8, c.backlog()); assertEquals(1, c.alpha());
        for (int frame = 0; frame < 4; frame++) {
            c.beginFrame(world, 10, .4f, false);
            while (c.hasStep()) c.commit(c.nextTick());
        }
        assertEquals(10, c.completedTick()); assertEquals(.4f, c.alpha());
    }
    @Test void abortedSubmissionRetriesTheSameTickAndPauseDoesNotAdvance() {
        var c = new ParticleSimulationClock(); var world = new Object();
        c.beginFrame(world, 100, .25f, false); assertEquals(100, c.nextTick());
        c.beginFrame(world, 100, .25f, false); assertEquals(100, c.nextTick()); c.commit(100);
        c.beginFrame(world, 101, .25f, true); assertFalse(c.hasStep());
        c.beginFrame(world, 101, .25f, false); assertEquals(101, c.nextTick());
        assertThrows(IllegalStateException.class, () -> c.commit(102));
    }
    @Test void worldAndClockDiscontinuitiesInvalidateHistory() {
        var c = new ParticleSimulationClock(); var a = new Object();
        c.beginFrame(a, 500, 0, false); c.commit(500); long epoch = c.epoch();
        c.beginFrame(new Object(), 1, Float.NaN, false);
        assertTrue(c.epoch() > epoch); assertEquals(1, c.nextTick()); c.commit(1); assertEquals(0, c.alpha());
        c.beginFrame(null, 0, 0, false); assertFalse(c.hasStep());
    }
}
