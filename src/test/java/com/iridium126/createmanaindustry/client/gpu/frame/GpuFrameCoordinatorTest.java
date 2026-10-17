package com.iridium126.createmanaindustry.client.gpu.frame;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GpuFrameCoordinatorTest {
    private void prepare(GpuFrameCoordinator frame) { frame.begin();frame.simulate();frame.generate();frame.cullSort(); }
    private void publish(GpuFrameCoordinator frame,long generation) { frame.publish(generation,1,2,3,4,5,generation,generation,-1); }

    @Test void everyPhaseMustCompleteBeforeTheSinglePublicationAndDraw() {
        var frame=new GpuFrameCoordinator();frame.begin();
        assertThrows(IllegalStateException.class,frame::generate);
        assertThrows(IllegalStateException.class,()->publish(frame,1));
        frame.simulate();frame.generate();frame.cullSort();publish(frame,1);
        var snapshot=frame.publication();assertNotNull(snapshot);assertEquals(1,snapshot.generation());
        assertThrows(IllegalStateException.class,()->publish(frame,2));
        frame.draw();assertSame(snapshot,frame.publication());
        assertThrows(IllegalStateException.class,frame::draw);
        frame.finishDraw();assertNull(frame.publication());
    }

    @Test void failedOrNewPreparationNeverExposesThePreviousPublication() {
        var frame=new GpuFrameCoordinator();prepare(frame);publish(frame,1);var old=frame.publication();
        prepare(frame);assertNull(frame.publication());frame.abort();assertNull(frame.publication());
        assertThrows(IllegalStateException.class,frame::draw);
        prepare(frame);publish(frame,2);assertTrue(frame.publication().frame()>old.frame());
        assertEquals(1,old.generation()); // a consumer's tuple is immutable
    }

    @Test void eachAttachedDomainMustBelongToThePublishedGeneration() {
        var frame=new GpuFrameCoordinator();prepare(frame);
        assertThrows(IllegalArgumentException.class,()->frame.publish(9,1,2,3,4,5,8,9,9));
        assertNull(frame.publication());
        assertThrows(IllegalArgumentException.class,()->frame.publish(9,1,2,3,4,5,9,8,9));
        assertThrows(IllegalArgumentException.class,()->frame.publish(9,1,2,3,4,5,9,9,8));
        assertThrows(IllegalArgumentException.class,()->frame.publish(9,0,2,3,4,5,9,-1,-1));
        frame.publish(9,1,0,3,4,5,9,-1,-1);assertNotNull(frame.publication());
    }
}
