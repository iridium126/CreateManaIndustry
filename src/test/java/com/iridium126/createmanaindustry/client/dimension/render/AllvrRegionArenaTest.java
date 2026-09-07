package com.iridium126.createmanaindustry.client.dimension.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AllvrRegionArenaTest {

    @Test
    void reusesFreedRangesWithAChangedGeneration() {
        AllvrRegionArena arena = new AllvrRegionArena(8);
        arena.ensureCapacity(16);
        AllvrRegionArena.Handle first = arena.allocate(4);
        AllvrRegionArena.Handle second = arena.allocate(4);
        assertNotNull(first);
        assertNotNull(second);

        arena.free(first.offset(), first.length());
        AllvrRegionArena.Handle reused = arena.allocate(3);
        assertEquals(first.offset(), reused.offset());
        assertNotEquals(first.generation(), reused.generation());
        assertTrue(arena.canFit(1));
    }

    @Test
    void allocationDoesNotCrossRegionBoundary() {
        AllvrRegionArena arena = new AllvrRegionArena(4);
        arena.ensureCapacity(8);
        assertNotNull(arena.allocate(4));
        AllvrRegionArena.Handle next = arena.allocate(2);
        assertNotNull(next);
        assertEquals(4, next.offset());
    }
}
