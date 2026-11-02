package com.iridium126.createmanaindustry.client.particles.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UpdateFrameInputsTest {
    @Test void alignedViewsDoNotOverlap(){
        for(int alignment:new int[]{16,64,256,1024})for(int players:new int[]{16,112,16384}){
            var l=UpdateFrameInputs.Layout.of(320,players,92,alignment);
            assertEquals(0,l.playerOffset()%alignment);assertEquals(0,l.damageOffset()%alignment);
            assertTrue(l.playerOffset()>=320);assertTrue(l.damageOffset()>=l.playerOffset()+players);
            assertTrue(l.bytes()>=l.damageOffset()+92);assertEquals(0,l.bytes()%16);
        }
        assertThrows(IllegalArgumentException.class,()->UpdateFrameInputs.Layout.of(17,16,16,256));
        assertThrows(IllegalArgumentException.class,()->UpdateFrameInputs.Layout.of(16,16,16,12));
        assertThrows(ArithmeticException.class,()->UpdateFrameInputs.Layout.of(Integer.MAX_VALUE-15,256,16,256));
    }
}
