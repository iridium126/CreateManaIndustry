package com.iridium126.createmanaindustry.client.particles.packages;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageFreeHoverPickTest {
    @Test void currentRayMustIntersectTheGpuSelectedBoxWithinItsOcclusionLimitedSegment() {
        var ray=new PackagePoseQueryGpu.Ray(0,0,-2,0,0,4);
        var hit=PackageFreeHoverPick.intersection(ray,0,0,0,0,0,0,.5f,.5f);
        assertNotNull(hit);assertEquals(-.5,hit.z,1e-6);
        assertNull(PackageFreeHoverPick.intersection(new PackagePoseQueryGpu.Ray(0,0,-2,0,0,1.2f),
                0,0,0,0,0,0,.5f,.5f));
    }
    @Test void rayStartingInsideUsesTheCurrentEyeAndInvalidDimensionsFailClosed() {
        var ray=new PackagePoseQueryGpu.Ray(0,0,0,0,0,4);
        var hit=PackageFreeHoverPick.intersection(ray,10,20,30,0,0,0,.5f,.5f);
        assertNotNull(hit);assertEquals(10,hit.x,1e-6);assertEquals(20,hit.y,1e-6);assertEquals(30,hit.z,1e-6);
        assertNull(PackageFreeHoverPick.intersection(ray,0,0,0,0,0,0,Float.NaN,.5f));
    }
}
