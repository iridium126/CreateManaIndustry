package com.iridium126.createmanaindustry.compat.hexcasting;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResourceConversionMathTest {
    @Test
    void displayedPointsAreScaledExactlyOnce() {
        assertEquals(64.0, ResourceConversionMath.rate(25.0));
        assertEquals(new ResourceConversionMath.Amounts(10000, 64000),
                ResourceConversionMath.mediaToMana(1, 25, 1000));
        assertEquals(new ResourceConversionMath.Amounts(64000, 10000),
                ResourceConversionMath.manaToMedia(64, 25, 1000));
        assertEquals(new ResourceConversionMath.Amounts(640, 10000),
                ResourceConversionMath.manaToMedia(64, 25, 10));
    }

    @Test
    void fractionalStorageUnitsFollowNativeTruncation() {
        assertEquals(new ResourceConversionMath.Amounts(1, 6),
                ResourceConversionMath.mediaToMana(0.0001, 25, 1000));
        assertEquals(new ResourceConversionMath.Amounts(7, 1),
                ResourceConversionMath.manaToMedia(0.007, 25, 1000));
        assertEquals(new ResourceConversionMath.Amounts(64000, 10000),
                ResourceConversionMath.manaToMedia(64.0009, 25, 1000));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceConversionMath.manaToMedia(0.001, 25, 1000));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceConversionMath.mediaToMana(0.00001, 25, 1000));
    }

    @Test
    void rateTracksLoadWithoutOverflowing() {
        assertEquals(31.4259, ResourceConversionMath.rate(0), 0.0001);
        assertTrue(ResourceConversionMath.rate(10) < ResourceConversionMath.rate(25));
        assertTrue(ResourceConversionMath.rate(25) < ResourceConversionMath.rate(50));
        assertEquals(102.4, ResourceConversionMath.rate(Double.MAX_VALUE), 1e-12);
    }

    @Test
    void rejectsInvalidInputsAndSaturatingLongCasts() {
        for (double input : new double[]{0, -1, Double.NaN, Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.MAX_VALUE, 0x1.0p63}) {
            assertThrows(IllegalArgumentException.class,
                    () -> ResourceConversionMath.mediaToMana(input, 25, 1000));
            assertThrows(IllegalArgumentException.class,
                    () -> ResourceConversionMath.manaToMedia(input, 25, 1000));
        }
        assertThrows(IllegalArgumentException.class,
                () -> ResourceConversionMath.mediaToMana(0x1.0p63 / 64000, 25, 1000));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceConversionMath.manaToMedia(0x1.0p63 / 1000, 25, 1000));
        assertThrows(IllegalArgumentException.class, () -> ResourceConversionMath.rate(-1));
        assertThrows(IllegalArgumentException.class, () -> ResourceConversionMath.rate(Double.NaN));
    }
}
