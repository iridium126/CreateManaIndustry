package com.iridium126.createmanaindustry.dimension.lod;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AllvrLodCodecPackingTest {

    @Test
    void canonicalPaletteWidthsAreContinuous() {
        assertEquals(1, AllvrLodSectionCodec.bitWidth(2));
        assertEquals(2, AllvrLodSectionCodec.bitWidth(3));
        assertEquals(2, AllvrLodSectionCodec.bitWidth(4));
        assertEquals(4, AllvrLodSectionCodec.bitWidth(5));
        assertEquals(4, AllvrLodSectionCodec.bitWidth(8));
        assertEquals(4, AllvrLodSectionCodec.bitWidth(9));
        assertEquals(8, AllvrLodSectionCodec.bitWidth(17));
        assertEquals(16, AllvrLodSectionCodec.bitWidth(257));
    }

    @Test
    void lsbPackingRoundTripsEverySupportedWidth() {
        for (int width : new int[] {1, 2, 4, 8, 16}) {
            int[] values = new int[AllvrLodSectionData.CELLS];
            int mask = (1 << width) - 1;
            for (int i = 0; i < values.length; i++) {
                values[i] = (i * 31 + (i >>> 5)) & mask;
            }
            assertArrayEquals(values, AllvrLodSectionCodec.unpack(
                AllvrLodSectionCodec.pack(values, width), width), "width " + width);
        }
    }
}
