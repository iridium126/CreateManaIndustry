package com.iridium126.createmanaindustry.client.greattrick;

import com.iridium126.createmanaindustry.compat.trickster.greattrick.GreatPattern;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GreatDrawingGestureTest {
    @Test void mouseDownOnDotIsConsumedAndDragReleaseCompletesStroke() {
        var gesture = new GreatDrawingGesture();
        assertTrue(gesture.press(7),"The drawing surface must consume mouse-down before the soup pans");
        gesture.move(11);
        assertEquals(List.of(7,8,9,10,11),gesture.path());
        assertEquals(GreatPattern.fromPath(List.of(7,11)),gesture.release());
        assertFalse(gesture.drawing());
        assertNull(gesture.release(),"A second release must not apply the same stroke again");
    }

    @Test void initialClickReleaseKeepsOriginalClickMoveClickWorkflow() {
        var gesture = new GreatDrawingGesture();
        assertTrue(gesture.press(0));
        assertNull(gesture.release());
        assertTrue(gesture.drawing());
        gesture.move(18);
        assertEquals(List.of(0,4,9,14,18),gesture.path());
        assertTrue(gesture.press(-1),"A second click in the canvas can finish without hitting another dot");
        assertEquals(GreatPattern.fromPath(List.of(0,18)),gesture.release());
        assertFalse(gesture.drawing());
    }

    @Test void backgroundPressStaysAvailableForSoupPanning() {
        var gesture = new GreatDrawingGesture();
        assertFalse(gesture.press(-1));
        assertFalse(gesture.press(19));
        gesture.move(9);
        assertFalse(gesture.drawing());
        assertNull(gesture.release());
    }

    @Test void backtrackingAndCancelDoNotLeaveAStuckDrawingCapture() {
        var gesture = new GreatDrawingGesture();
        gesture.press(7);
        gesture.move(11);
        gesture.move(7);
        assertEquals(GreatPattern.EMPTY,gesture.release());
        gesture.press(0);
        gesture.release();
        gesture.move(18);
        gesture.cancel();
        assertFalse(gesture.drawing());
        assertNull(gesture.path());
        assertNull(gesture.release());
        assertTrue(gesture.press(9),"Drawing can restart after Escape cancels the previous stroke");
    }

    @Test void twoClickWorkflowDoesNotAutoCompleteWhenPointerMovesWithoutButtonHeld() {
        var gesture = new GreatDrawingGesture();
        gesture.press(0);
        gesture.release();
        for (int dot : List.of(4,9,14,18,15,11,10,9,8,7,3,0)) gesture.move(dot);
        assertTrue(gesture.drawing());
        assertTrue(gesture.press(0));
        assertEquals(GreatPattern.INVERSION,gesture.release());
    }
}
