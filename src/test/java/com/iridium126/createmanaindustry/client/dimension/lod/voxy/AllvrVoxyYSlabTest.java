package com.iridium126.createmanaindustry.client.dimension.lod.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AllvrVoxyYSlabTest {

    @Test
    void shiftsTheInitialWindowDownBy512Blocks() {
        assertEquals(-512, AllvrVoxyYSlab.WINDOW_SHIFT_BLOCKS);
        assertEquals(3_584, AllvrVoxyYSlab.slabCenterBlockY(0));
        assertEquals(224, AllvrVoxyYSlab.SECTION_CENTER_OFFSET);
    }

    @Test
    void selectsShiftedSlabBoundaries() {
        assertEquals(0, AllvrVoxyYSlab.slabIdForBlockY(-512));
        assertEquals(0, AllvrVoxyYSlab.slabIdForBlockY(7_679));
        assertEquals(-1, AllvrVoxyYSlab.slabIdForBlockY(-513));
        assertEquals(1, AllvrVoxyYSlab.slabIdForBlockY(7_680));
    }

    @Test
    void mapsTheShiftedAbsoluteSectionWindow() {
        assertEquals(-256, AllvrVoxyYSlab.virtualSectionY(0, -32));
        assertEquals(255, AllvrVoxyYSlab.virtualSectionY(0, 479));
        assertTrue(AllvrVoxyYSlab.containsSectionY(0, -32));
        assertTrue(AllvrVoxyYSlab.containsSectionY(0, 479));
        assertFalse(AllvrVoxyYSlab.containsSectionY(0, -33));
        assertFalse(AllvrVoxyYSlab.containsSectionY(0, 480));
    }

    @Test
    void keepsCameraAtVirtualOriginAtShiftedCenter() {
        assertEquals(0.0D, AllvrVoxyYSlab.virtualCameraY(0, 3_584.0D));
    }
}
