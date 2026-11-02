package com.iridium126.createmanaindustry.client.particles.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StormShadowSnapshotTest {
    @Test void cylinderIsBoundToUpdateGeneration(){
        var input=StormShadowSnapshot.of(81,true,1,2,3,64);
        assertTrue(input.matches(81));assertFalse(input.matches(82));
        assertEquals(74,input.radius(),.001);assertEquals(-5.6,input.low(),.001);assertEquals(10.4,input.high(),.001);
        assertFalse(StormShadowSnapshot.of(81,false,1,2,3,64).matches(81));
        assertFalse(StormShadowSnapshot.of(81,true,Double.NaN,2,3,64).matches(81));
    }
}
