package com.iridium126.createmanaindustry.client.particles.engine.hardware;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import com.iridium126.createmanaindustry.client.particles.engine.ParticleShaderSource;

class HardwareDomeSourceTest {
    private String source(){return ParticleShaderSource.loadParticle("dome_contacts.comp",path->{try{return Files.readString(Path.of("src/main/resources/assets/createmanaindustry",path));}catch(Exception e){throw new IllegalStateException(e);}});}
    @Test void replacesBroadPhaseAndRetainsSweptRules(){
        var v=HardwareDomeSource.build(source());
        assertFalse(v.consumer().contains("++visits>768"));
        assertTrue(v.consumer().contains("cmiDomeSweepFraction(old-otherOld,motion-otherMotion)"));
        assertTrue(v.consumer().contains("otherMember<r7GatherMember"));
        assertTrue(v.consumer().contains("otherMember<r7BlockingMember"));
        assertTrue(v.consumer().contains("min(uint(visits),65535u)"));
        assertTrue(v.prepare().contains("CMI_BODY_RADIUS+budget*uDt+other"));
        assertTrue(v.prepare().contains("move+max(move,budget*uDt)"));
        assertTrue(v.prepare().contains("r7Put(1u,2u)"));
    }
    @Test void failsClosedWhenTheOriginalQueryBoundaryChanges(){
        assertThrows(IllegalArgumentException.class,()->HardwareDomeSource.build(source().replace("while(node!=0){","while(node>0){")));
    }
}
