package com.iridium126.createmanaindustry.content.allaystorm;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;
import static org.junit.jupiter.api.Assertions.*;
class MajorAttackScheduleTest {
    private static final DoubleSupplier NO_RANDOM = () -> { throw new AssertionError("Unexpected random sample"); };

    @Test void independentClocksConsumeFailedAndSkippedOpportunities() {
        var s = new MajorAttackSchedule();
        assertFalse(s.due(StormAttack.HELIX, 100, 30, 30, NO_RANDOM));
        assertFalse(s.due(StormAttack.DOME, 100, 60, 60, NO_RANDOM));
        assertFalse(s.due(StormAttack.HELIX, 699, 30, 30, NO_RANDOM));
        assertTrue(s.due(StormAttack.HELIX, 700, 30, 30, NO_RANDOM));
        assertFalse(s.due(StormAttack.DOME, 700, 60, 60, NO_RANDOM));
        assertFalse(s.due(StormAttack.HELIX, 700, 30, 30, NO_RANDOM));
        assertTrue(s.due(StormAttack.DOME, 1300, 60, 60, NO_RANDOM));
        assertTrue(s.due(StormAttack.HELIX, 5000, 30, 30, NO_RANDOM));
        assertFalse(s.due(StormAttack.HELIX, 5000, 30, 30, NO_RANDOM));
        assertFalse(s.due(StormAttack.HELIX, 5599, 30, 30, NO_RANDOM));
        assertTrue(s.due(StormAttack.HELIX, 5600, 30, 30, NO_RANDOM));
    }

    @Test void samplesOnlyOnInitializationAndEachOpportunity() {
        var s = new MajorAttackSchedule();
        var calls = new AtomicInteger();
        double[] samples = {0, .5, Math.nextDown(1.)};
        DoubleSupplier random = () -> samples[calls.getAndIncrement()];
        assertFalse(s.due(StormAttack.HELIX, 0, 30, 60, random));
        for (int tick = 0; tick < 600; tick++) assertFalse(s.due(StormAttack.HELIX, tick, 30, 60, random));
        assertEquals(1, calls.get());
        assertTrue(s.due(StormAttack.HELIX, 600, 30, 60, random));
        assertFalse(s.due(StormAttack.HELIX, 1499, 30, 60, random));
        assertTrue(s.due(StormAttack.HELIX, 1500, 30, 60, random));
        assertFalse(s.due(StormAttack.HELIX, 2699, 30, 60, NO_RANDOM));
        assertEquals(3, calls.get());
        assertTrue(s.due(StormAttack.HELIX, 2700, 30, 30, NO_RANDOM));
    }

    @Test void reversedBoundsAndReloadPreservePendingDeadline() {
        var s = new MajorAttackSchedule();
        assertFalse(s.due(StormAttack.DOME, 0, 90, 60, () -> .25)); // 67.5 s
        assertFalse(s.due(StormAttack.DOME, 1349, 5, 10, NO_RANDOM));
        assertTrue(s.due(StormAttack.DOME, 1350, 5, 10, () -> .5)); // new range: 7.5 s
        assertFalse(s.due(StormAttack.DOME, 1499, 5, 10, NO_RANDOM));
        assertTrue(s.due(StormAttack.DOME, 1500, 5, 5, NO_RANDOM));
    }

    @Test void fractionalIntervalsRoundToServerTicks() {
        var s = new MajorAttackSchedule();
        assertFalse(s.due(StormAttack.DOME, 0, 5.02, 5.02, NO_RANDOM));
        assertFalse(s.due(StormAttack.DOME, 99, 5.02, 5.02, NO_RANDOM));
        assertTrue(s.due(StormAttack.DOME, 100, 5.02, 5.02, NO_RANDOM));
    }

    @Test void opportunitiesRotateAndResetClearsAllClocksAndTargets() {
        var s = new MajorAttackSchedule();
        s.due(StormAttack.HELIX, 0, 30, 30, NO_RANDOM);
        s.due(StormAttack.DOME, 0, 30, 30, NO_RANDOM);
        for (int i = 1; i <= 6; i++) {
            assertTrue(s.ready(i * 600));
            assertEquals((i - 1) % 3, s.first());
            assertTrue(s.due(StormAttack.HELIX, i * 600, 30, 30, NO_RANDOM));
            assertTrue(s.due(StormAttack.DOME, i * 600, 30, 30, NO_RANDOM));
            assertFalse(s.ready(i * 600));
        }
        for (int i = 0; i < 7; i++) {
            assertEquals(i % 3, s.target(StormAttack.HELIX, 3));
            assertEquals(i % 3, s.target(StormAttack.DOME, 3));
        }
        s.first();
        s.reset();
        assertFalse(s.ready(10000));
        assertEquals(0, s.target(StormAttack.HELIX, 3));
        assertEquals(0, s.target(StormAttack.DOME, 3));
        assertEquals(List.of(0, 1, 2, 0), java.util.stream.IntStream.range(0, 4).map(i -> s.first()).boxed().toList());
        assertFalse(s.due(StormAttack.DOME, 10000, 60, 90, () -> 0));
        assertFalse(s.due(StormAttack.DOME, 11199, 60, 90, NO_RANDOM));
        assertTrue(s.due(StormAttack.DOME, 11200, 60, 60, NO_RANDOM));
    }
}
