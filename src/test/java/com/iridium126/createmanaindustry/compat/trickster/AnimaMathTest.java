package com.iridium126.createmanaindustry.compat.trickster;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnimaMathTest {
    @Test void capacityUsesActualDustEquivalentAndPreservesFractionalAnima() {
        assertEquals(6400000,AnimaMath.capacity(640000000,10000,0.1));
        assertEquals(4000,AnimaMath.capacity(400000,10000,0.1));
        assertEquals(125,AnimaMath.capacity(12500,10000,0.1));
        assertEquals(0,AnimaMath.capacity(0,10000,0.1));
        assertEquals(0,AnimaMath.capacity(640000000,10000,0));
    }
    @Test void conversionsDoubleOnceAndRoundUpAtInternalPrecision() {
        assertEquals(102400,AnimaMath.exchange(10000,2048000,400000));
        assertEquals(400000,AnimaMath.exchange(1024000,400000,2048000));
        assertEquals(1,AnimaMath.exchange(1,1,10000));
        assertEquals(Long.MAX_VALUE,AnimaMath.exchange(Long.MAX_VALUE,Long.MAX_VALUE,1));
        assertEquals("0.032",AnimaMath.format(32));assertEquals("4",AnimaMath.format(4000));
    }
}
