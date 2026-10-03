package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class PackageSableCollisionSectionsTest {
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
