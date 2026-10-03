package com.iridium126.createmanaindustry.client.particles.packages;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageSableCollisionPolicyTest {
    @Test void onlyFinalizedPopulatedPlotsBecomeSharedMovingCollisionSources() {
        assertTrue(PackageSableCollisionPolicy.trackable(false,true,true,true));
        assertFalse(PackageSableCollisionPolicy.trackable(false,false,true,true),"an initializing sub-level must not poison world history");
        assertFalse(PackageSableCollisionPolicy.trackable(true,true,true,true),"removed sub-levels are not collision sources");
        assertFalse(PackageSableCollisionPolicy.trackable(false,true,false,false),"a missing plot is local to that sub-level");
        assertFalse(PackageSableCollisionPolicy.trackable(false,true,true,false),"an empty/unloaded plot must not block unrelated packages");
    }

    @Test void anEmptyMovingSceneStillProducesReadyHistoricalFrames() {
        var cache = new PackageMovingCollisionCache(Runnable::run,1);
        cache.captureHistory(20,true);
        assertTrue(cache.history(20).isEmpty());
        assertNotNull(cache.simulationFrame(20,false));
    }
}
