package com.iridium126.createmanaindustry.client.particles.emitter;

import java.util.HashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmitterSpecImmutabilityTest {
    @Test void headerCopiesCannotMutateCatalogKeysAndStylesDeduplicateSeparately() {
        var spray=EmitterSpec.builder().shaderColorMode(5).spawnStyle(4).build();
        var direct=EmitterSpec.builder().shaderColorMode(5).spawnStyle(5).build();
        var catalog=new HashMap<EmitterSpec,Integer>();catalog.put(spray,1);catalog.put(direct,2);
        int original=spray.hashCode();
        spray.packed()[17*4+1]=5;
        assertEquals(original,spray.hashCode());assertEquals(4,spray.packed()[17*4+1]);
        assertEquals(1,catalog.get(EmitterSpec.builder().shaderColorMode(5).spawnStyle(4).build()));
        assertEquals(2,catalog.get(direct));assertNotEquals(spray,direct);
        assertEquals(80,spray.packed().length);
    }
}
