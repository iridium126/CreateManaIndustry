package com.iridium126.createmanaindustry.client.particles.engine;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ParticleHitRayTest {
    @Test void completedRayKeepsItsOriginalContactAfterCameraMovement(){
        var completed=new ParticleHitRay(12,64,-8,0,0,1,4);
        var newer=new ParticleHitRay(15,65,-9,1,0,0,4);
        assertEquals(new net.minecraft.world.phys.Vec3(12,64,-5),completed.point(3));
        assertNotEquals(newer.point(3),completed.point(3));
    }
    @Test void delayedQueryCannotBeReinterpretedThroughANewCamera(){
        var ray=new ParticleHitRay(0,1,0,0,0,1,3);assertTrue(ray.matches(ray));
        assertFalse(ray.matches(new ParticleHitRay(0,1,0,1,0,0,3)));
        assertFalse(ray.matches(new ParticleHitRay(1,1,0,0,0,1,3)));
        assertFalse(ray.matches(new ParticleHitRay(0,1,0,0,0,1,5)));
        assertFalse(ray.matches(null));
        assertTrue(ray.matches(new ParticleHitRay(0,1,.05f,0,0,1,3)));
    }
}
