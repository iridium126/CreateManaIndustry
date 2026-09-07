package com.iridium126.createmanaindustry.client.dimension.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AllvrRenderRevisionTest {

    @Test
    void staleResultCannotPublishAfterAnotherDirty() {
        AllvrRenderCell cell = new AllvrRenderCell(AllvrRenderCellKey.ofCell(-2, 17, 3));
        long first = cell.markDirty();
        assertTrue(cell.schedule(first));
        assertTrue(cell.begin(first));

        long second = cell.markDirty();
        assertFalse(cell.publish(first, 10, 4));
        assertTrue(cell.schedule(second));
        assertTrue(cell.begin(second));
        assertTrue(cell.publish(second, 20, 6));
        assertTrue(cell.publishedRevision() == second);
    }

    @Test
    void failedBuildLeavesPublishedAllocationUntouched() {
        AllvrRenderCell cell = new AllvrRenderCell(AllvrRenderCellKey.ofCell(0, 0, 0));
        long revision = cell.markDirty();
        assertTrue(cell.schedule(revision));
        assertTrue(cell.begin(revision));
        assertTrue(cell.publish(revision, 3, 2));

        long next = cell.markDirty();
        assertTrue(cell.schedule(next));
        assertTrue(cell.fail(next));
        assertTrue(cell.quadStart() == 3);
        assertTrue(cell.quadCount() == 2);
    }

    @Test
    void worldSessionInvalidatesCellsAndResourceRevisionIsMonotonic() {
        AllvrRenderWorld world = new AllvrRenderWorld();
        long key = AllvrRenderCellKey.ofCell(4, -8, 2);
        world.markCellDirty(key);
        long epoch = world.epoch();
        long resource = world.resourceRevision();
        assertTrue(world.cellCount() == 1);
        assertTrue(world.bumpResourceRevision() > resource);
        world.beginSession();
        assertTrue(world.epoch() > epoch);
        assertTrue(world.cellCount() == 0);
    }
}
