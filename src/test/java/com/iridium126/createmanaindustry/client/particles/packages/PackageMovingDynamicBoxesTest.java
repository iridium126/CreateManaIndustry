package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PackageMovingDynamicBoxesTest {
    @Test void copiesValidatedUnitVoxelBoxesIntoImmutableMovingGeometry() {
        var boxes=PackageMovingDynamicBoxes.capture(sink->{
            sink.add(.125,.25,.375,.875,.75,.625);
            sink.add(0,0,0,.25,.25,.25);
        },100,20,-30,96,16,-32,.8f);
        assertEquals(2,boxes.size());
        var first=boxes.get(0);
        assertEquals(4.125f,first.x0());assertEquals(4.25f,first.y0());assertEquals(2.375f,first.z0());
        assertEquals(4.875f,first.x1());assertEquals(4.75f,first.y1());assertEquals(2.625f,first.z1());
        assertEquals(.8f,first.friction());assertEquals(0,first.flags());
        assertThrows(UnsupportedOperationException.class,()->boxes.clear());
    }

    @Test void emptyAndExplicitClearRemainEmpty() {
        assertTrue(PackageMovingDynamicBoxes.capture(sink->{},1,2,3,0,0,0,.6f).isEmpty());
        var boxes=PackageMovingDynamicBoxes.capture(sink->{sink.add(0,0,0,1,1,1);sink.clear();},1,2,3,0,0,0,.6f);
        assertTrue(boxes.isEmpty());
    }

    @Test void invalidOrOversizedGeometryFailsClosedToItsOwningVoxel() {
        assertUnsupported(sink->sink.add(-.01,0,0,.5,.5,.5));
        assertUnsupported(sink->sink.add(0,0,0,1.01,1,1));
        assertUnsupported(sink->sink.add(0,0,0,Double.NaN,1,1));
        assertUnsupported(sink->{
            for(int i=0;i<=PackageMovingDynamicBoxes.MAX_BOXES_PER_BLOCK;i++)sink.add(0,0,0,1,1,1);
        });
        assertUnsupported(sink->{throw new LinkageError("dynamic collider unavailable");});
    }

    private static void assertUnsupported(PackageMovingDynamicBoxes.Builder builder) {
        var boxes=PackageMovingDynamicBoxes.capture(builder,-4,7,12,-8,4,8,.6f);
        assertEquals(1,boxes.size());var box=boxes.getFirst();
        assertEquals(4,box.x0());assertEquals(3,box.y0());assertEquals(4,box.z0());
        assertEquals(5,box.x1());assertEquals(4,box.y1());assertEquals(5,box.z1());
        assertEquals(PackageCollisionCache.UNSUPPORTED,box.flags());
    }
}
