package com.iridium126.createmanaindustry.content.allaystorm;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StormStreamClockTest {
    private final ResourceLocation dimension = ResourceLocation.parse("minecraft:overworld");

    @Test void rejectsOldIdentityAndTimeWithoutPoisoningCurrentStream() {
        var clock = new StormStreamClock();
        var active = new StormIdentity(dimension, 2);
        assertTrue(clock.accept(active, active, dimension, 100));
        assertFalse(clock.accept(new StormIdentity(dimension, 1), active, dimension, 200));
        assertFalse(clock.accept(active, active, ResourceLocation.parse("minecraft:the_nether"), 200));
        assertFalse(clock.accept(active, active, dimension, 99));
        assertTrue(clock.accept(active, active, dimension, 100));
        assertTrue(clock.accept(active, active, dimension, 101));
        assertFalse(clock.accept(active, null, dimension, 102));
        var replacement = new StormIdentity(dimension, 3);
        assertTrue(clock.accept(replacement, replacement, dimension, 1));
        clock.reset();
        assertTrue(clock.accept(replacement, replacement, dimension, 0));
    }
}
