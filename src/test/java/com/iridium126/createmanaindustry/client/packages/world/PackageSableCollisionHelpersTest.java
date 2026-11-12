package com.iridium126.createmanaindustry.client.packages.world;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Sable plot collision helpers: coordinate conversion, trackable policy and sparse sections. */
class PackageSableCollisionHelpersTest {
    @Test void embeddedReaderReceivesCoordinatesRelativeToThePlotCenter() {
        var storage = new BlockPos(30_000_123, 128, -29_999_877);
        var center = new BlockPos(30_000_008, 96, -29_999_992);
        var target = new BlockPos.MutableBlockPos();

        assertSame(target, PackageSableCollisionCoordinates.contextPosition(storage, center, target));
        assertEquals(new BlockPos(115, 32, 115), target);
        assertEquals(storage, target.offset(center));
    }

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

    @Test void skipsSparseSectionsAndStopsAtTheFirstPresentSection() {
        String[] sections = {null, null, "solid", null, "water"};

        assertEquals(2, PackageSableCollisionSections.nextNonNull(sections, 0));
        assertEquals(4, PackageSableCollisionSections.nextNonNull(sections, 3));
        assertEquals(5, PackageSableCollisionSections.nextNonNull(sections, 5));
    }

    @Test void anAllNullPlotChunkIsAValidEmptyChunk() {
        String[] sections = new String[24];

        assertEquals(sections.length, PackageSableCollisionSections.nextNonNull(sections, 0));
    }
}
