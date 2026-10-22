package com.iridium126.createmanaindustry.content.allaystorm;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StormMotionTest {
    @Test void rotationAndVelocityHaveMatchingHandednessThroughoutGrowth(){
        for(int seed:new int[]{0,1,123,124})for(long tick:new long[]{0,1000,10000,100000}){
            double count=Math.min(65536,16384+tick);
            var before=AllayStormPhases.evaluate(16,32,20,0,tick,count,seed);
            var after=AllayStormPhases.evaluate(16,32,20,0,tick+20,Math.min(65536,count+20),seed);
            double delta=Math.atan2(Math.sin(after.rotation()-before.rotation()),Math.cos(after.rotation()-before.rotation()));
            assertEquals(before.omega(),delta,.0002);
            var opposite=AllayStormPhases.evaluate(16,32,20,0,tick,count,seed^1);
            assertEquals(-before.omega(),opposite.omega());assertEquals(Math.cos(before.rotation()),Math.cos(opposite.rotation()),1e-7);
        }
    }
    @Test void templatePitchHasHeadroomInsideThirtyTwoMetresPerSecond(){
        assertEquals(.75,StormMotion.HELIX_TURNS);
        assertEquals(50.625,Math.toDegrees(StormMotion.helixPitch()*StormMotion.HELIX_THRUST),1e-6);
        assertEquals(-2*Math.PI,StormMotion.helixAngle(64/.75),1e-6);
        assertTrue(Math.hypot(12,32*StormMotion.helixPitch()*12)<31);
        for(int rank=0;rank<4096;rank++)assertTrue(Math.hypot(12,Math.hypot(HelixTemplate.coordinate(rank,1),HelixTemplate.coordinate(rank,2))*StormMotion.helixPitch()*12)<32);
    }
    @Test void removedHelixSpeedConfigurationCannotOverrideFixedThrust() throws Exception {
        var type=Class.forName("com.iridium126.createmanaindustry.infrastructure.config.ServerConfig",false,getClass().getClassLoader());
        for(var field:type.getDeclaredFields())assertNotEquals("stormHelixThrustSpeed",field.getName());
    }
    @Test void snapshotLayoutIsUnchangedAndSharedShaderUsesMatchingSpeeds() throws Exception {
        assertEquals(30,StarfallColumnSnapshot.STRIDE);assertEquals(7680,StarfallColumnSnapshot.BYTES);
        try(var source=getClass().getResourceAsStream("/assets/createmanaindustry/shaders/particles/chunks/storm_speeds.glsl")){
            assertNotNull(source);String text=new String(source.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(text.contains("CMI_STORM_INTERCEPT=8."));assertTrue(text.contains("CMI_COLUMN_ORBIT_SPEED=3."));assertTrue(text.contains("CMI_HELIX_SPEED_LIMIT=32."));assertTrue(text.contains("CMI_HELIX_TURNS=.75"));
        }
    }
}
