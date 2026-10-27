package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.client.allaystorm.rift.JuliaShape;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JuliaResourceLayoutTest {
    @Test void runtimeReadsBundledDataWithoutClientAssetsOrOfflineReport()throws Exception {
        var loader=JuliaShape.class.getClassLoader();
        assertNotNull(loader.getResource("data/createmanaindustry/quadratic-c050.bin"));
        assertNotNull(loader.getResource("data/createmanaindustry/quadratic-c050-fit.json"));
        assertNull(loader.getResource("data/createmanaindustry/quadratic-c050.json"));
        for(String name:new String[]{"quadratic-c050.bin","quadratic-c050-fit.json","quadratic-c050.json"})
            assertNull(loader.getResource("assets/createmanaindustry/julia/"+name));
        var shape=JuliaShape.load();
        assertTrue(shape.segments.length>0);assertEquals(0,shape.segments.length%4);
        assertTrue(shape.referenceValid(16,128,384));
        assertEquals(shape.entranceScale,shape.scale(16,128,384,0));
        assertTrue(Double.isFinite(shape.scale(16,128,384,2)));
        assertTrue(shape.scale(16,128,384,2)>shape.entranceScale);
    }
}
