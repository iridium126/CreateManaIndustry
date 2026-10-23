package com.iridium126.createmanaindustry.content.allaystorm;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DomeOverlapTest {
    @Test void lowTargetsAreRejectedButClearUpperAndLowerGatheringVolumesAreAllowed(){
        for(double radius:new double[]{2,16,32,45.254834,64}){
            assertTrue(AllayDome.overlapsStormBody(0,0,radius));
            assertTrue(AllayDome.overlapsStormBody(0,-8,radius));
            assertTrue(AllayDome.overlapsStormBody(0,-16,radius));
            assertFalse(AllayDome.overlapsStormBody(0,-40,radius));
            assertFalse(AllayDome.overlapsStormBody(0,16,radius));
            assertFalse(AllayDome.overlapsStormBody(200,0,radius));
        }
    }
    @Test void hemisphereCylinderDistanceIncludesBodyAndJitterMargins(){
        double radius=32,reach=1.15*radius+.75,bottom=-5.9,top=11.3;
        assertTrue(AllayDome.overlapsStormBody(reach+32,0,radius));
        assertFalse(AllayDome.overlapsStormBody(reach+32+.0001,0,radius));
        assertTrue(AllayDome.overlapsStormBody(0,bottom-32,radius));
        assertFalse(AllayDome.overlapsStormBody(0,bottom-32-.0001,radius));
        assertTrue(AllayDome.overlapsStormBody(0,top,radius));
        assertFalse(AllayDome.overlapsStormBody(0,top+.0001,radius));
        assertTrue(AllayDome.overlapsStormBody(reach+19.1999,bottom-25.6,radius));
        assertFalse(AllayDome.overlapsStormBody(reach+19.2,bottom-25.6001,radius));
        assertThrows(IllegalArgumentException.class,()->AllayDome.overlapsStormBody(Double.NaN,0,radius));
    }
}
