package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CastSoundKeyTest {
    private final Object world = new Object(), player = new Object(), sound = new Object(), source = new Object();
    private CastSoundKey key(double x, float volume, float pitch) {
        return CastSoundKey.of(world, player, sound, source, x, 2, 3, volume, pitch);
    }

    @Test void allocationFreeMatchUsesAllCallbackParameters() {
        var original = key(1, 1, 1);
        assertTrue(original.matches(world, player, sound, source, 1, 2, 3, 1, 1));
        assertFalse(original.matches(world, player, new Object(), source, 1, 2, 3, 1, 1));
        assertFalse(original.matches(world, null, sound, source, 1, 2, 3, 1, 1));
        assertFalse(original.matches(world, player, sound, new Object(), 1, 2, 3, 1, 1));
        assertFalse(original.matches(world, player, sound, source, 1, 2.001, 3, 1, 1));
    }

    @Test void differentEmissionParametersRemainDistinct() {
        var original = key(1, 1, 1);
        assertNotEquals(original, key(1.0001, 1, 1));
        assertNotEquals(original, key(1, .5f, 1));
        assertNotEquals(original, key(1, 1, .5f));
        assertNotEquals(original, CastSoundKey.of(new Object(), player, sound, source, 1, 2, 3, 1, 1));
        assertNotEquals(key(0d, 1, 1), key(-0d, 1, 1));
    }
}
