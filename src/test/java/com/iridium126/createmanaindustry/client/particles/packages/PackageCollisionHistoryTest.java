package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageCollisionHistoryTest {
    private final PackageCollisionCache.Section section=new PackageCollisionCache.Section(-1,4,1);
    @Test void repeatedRevisionKeepsOneReferenceUntilItsLastFrameExpires(){
        var history=new PackageCollisionHistory();var first=new PackageCollisionGpu.GeometryRevision(section,1);
        history.capture(1,2,Map.of(section,1L),3);var retained=history.retained();
        history.capture(3,3,Map.of(section,1L),3);assertSame(retained,history.retained());
        history.capture(4,5,Map.of(section,2L),3);assertTrue(history.retained().contains(first));
        history.capture(6,6,Map.of(section,2L),3);assertFalse(history.retained().contains(first));
        assertTrue(retained.contains(first),"previous published reference set must stay immutable");
    }
    @Test void duplicateTicksAndMutableInputCannotRewriteRecordedGeometry(){
        var history=new PackageCollisionHistory();var mutable=new HashMap<PackageCollisionCache.Section,Long>();mutable.put(section,1L);
        history.capture(1,1,mutable,20);mutable.put(section,2L);history.capture(1,1,mutable,20);
        assertEquals(Map.of(section,1L),history.get(1));assertTrue(history.contains(1));assertNull(history.get(0));
        assertEquals(Set.of(new PackageCollisionGpu.GeometryRevision(section,1)),history.retained());
        assertThrows(UnsupportedOperationException.class,()->history.get(1).clear());
    }
    @Test void acceleratedInputIntervalsStayBoundedAndReleaseReferencesAfterRemoval(){
        var history=new PackageCollisionHistory();history.capture(0,9,Map.of(section,1L),200);
        for(int start=10;start<210;start+=10)history.capture(start,start+9,Map.of(),200);
        assertFalse(history.contains(9));assertTrue(history.contains(10));assertTrue(history.retained().isEmpty());
        assertThrows(IllegalArgumentException.class,()->history.capture(210,410,Map.of(),200));
    }
    @Test void consumingInputsReleasesOnlySubmittedTicksAndNeverDoubleReleases(){
        var history=new PackageCollisionHistory();var first=new PackageCollisionGpu.GeometryRevision(section,1);
        history.capture(1,3,Map.of(section,1L),3);history.consumed(2);assertTrue(history.retained().contains(first));
        history.consumed(3);assertTrue(history.retained().isEmpty());assertEquals(Map.of(section,1L),history.get(3));
        history.consumed(2);history.capture(3,3,Map.of(section,2L),3);assertTrue(history.retained().isEmpty());
        history.capture(4,4,Map.of(section,2L),3);assertEquals(Set.of(new PackageCollisionGpu.GeometryRevision(section,2)),history.retained());
        history.consumed(10);assertTrue(history.retained().isEmpty());
        history.capture(5,7,Map.of(section,3L),3);assertTrue(history.retained().isEmpty());
        history.capture(11,11,Map.of(section,4L),3);assertEquals(Set.of(new PackageCollisionGpu.GeometryRevision(section,4)),history.retained());
    }
}
