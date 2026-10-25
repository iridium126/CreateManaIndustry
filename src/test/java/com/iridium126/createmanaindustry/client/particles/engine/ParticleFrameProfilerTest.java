package com.iridium126.createmanaindustry.client.particles.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParticleFrameProfilerTest {
    @Test void stoppingAndResettingRestoreEmissionAndDiscardTheOldAverage() {
        var profiler=new ParticleFrameProfiler();profiler.setBudget(5);
        profiler.record(20,true);assertEquals(.85f,profiler.emissionScale());
        profiler.record(100,false);assertEquals(1,profiler.emissionScale());assertEquals(0,profiler.emaMs());
        profiler.record(1,true);assertEquals(1,profiler.emaMs());assertEquals(1,profiler.emissionScale());
        profiler.record(100,true);assertTrue(profiler.emissionScale()<1);
        profiler.reset();assertEquals(0,profiler.emaMs());assertEquals(1,profiler.emissionScale());
        assertEquals(5,profiler.budget());
    }
}
