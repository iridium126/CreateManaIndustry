package com.iridium126.createmanaindustry.client.particles.engine;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ParticleHitRayTest {
    @Test void delayedQueryCannotBeReinterpretedThroughANewCamera(){
        var ray=new ParticleHitRay(0,1,0,0,0,1,3);assertTrue(ray.matches(ray));
        assertFalse(ray.matches(new ParticleHitRay(0,1,0,1,0,0,3)));
        assertFalse(ray.matches(new ParticleHitRay(1,1,0,0,0,1,3)));
        assertFalse(ray.matches(new ParticleHitRay(0,1,0,0,0,1,5)));
        assertFalse(ray.matches(null));
        assertTrue(ray.matches(new ParticleHitRay(0,1,.05f,0,0,1,3)));
    }
}
