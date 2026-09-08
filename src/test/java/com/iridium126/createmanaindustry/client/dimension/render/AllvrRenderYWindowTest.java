package com.iridium126.createmanaindustry.client.dimension.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AllvrRenderYWindowTest {

    @Test
    void rebaseIsAlignedAndInvalidatesOldEpoch() {
        AllvrRenderYWindow window = new AllvrRenderYWindow();
        assertEquals(0, window.originBlockY());
        assertEquals(0, window.virtualBlockY(0));
        assertEquals(512, window.nextOrigin(700));

        long before = window.epoch();
        window.beginDetach();
        assertTrue(window.isDetaching());
        assertTrue(window.epoch() > before);

        window.publishMove(512);
        assertEquals(512, window.originBlockY());
        assertEquals(0, window.virtualBlockY(512));
        assertEquals(512, window.absoluteBlockY(0));
        assertEquals(AllvrRenderYWindow.Phase.REFILL, window.phase());
        window.endRebase();
        assertFalse(window.isRebasing());
    }

    @Test
    void initialOriginCanBeCenteredBeforeAnySectionsExist() {
        AllvrRenderYWindow window = new AllvrRenderYWindow();
        long before = window.epoch();

        window.initializeAt(window.nextOrigin(9_785));

        assertEquals(9_728, window.originBlockY());
        assertEquals(0, window.virtualBlockY(9_728));
        assertEquals(9_728, window.absoluteBlockY(0));
        assertEquals(AllvrRenderYWindow.Phase.STEADY, window.phase());
        assertTrue(window.epoch() > before);
    }
}
