package com.iridium126.createmanaindustry.dimension.gen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaletteVariantsTest {
    @Test void coordinateSeedsChangeByWorldAndPosition() {
        long first = BlockStateVariants.coordinateSeed(137L, 4, 95, -8);
        assertEquals(first, BlockStateVariants.coordinateSeed(137L, 4, 95, -8));
        assertNotEquals(first, BlockStateVariants.coordinateSeed(138L, 4, 95, -8));
        assertNotEquals(first, BlockStateVariants.coordinateSeed(137L, 5, 95, -8));
    }
}
