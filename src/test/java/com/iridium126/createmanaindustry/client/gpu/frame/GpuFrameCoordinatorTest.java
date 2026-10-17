package com.iridium126.createmanaindustry.client.gpu.frame;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GpuFrameCoordinatorTest {
    private void prepare(GpuFrameCoordinator frame) { frame.begin(0);frame.simulate();frame.generate();frame.cullSort(); }
    private void publish(GpuFrameCoordinator frame,long generation) { frame.publish(frame.candidate(generation,1,2,3,4,5,generation,generation,-1)); }

    @Test void everyPhaseMustCompleteBeforeTheSinglePublicationAndDraw() {
        var frame=new GpuFrameCoordinator();frame.begin(0);
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

    @Test void sceneChecksActualResourceVersionsWithoutEquatingBusinessClocks() {
        var frame=new GpuFrameCoordinator();prepare(frame);
        var candidate=frame.candidate(9,1,2,3,4,5,9,38,72);
        assertThrows(IllegalArgumentException.class,()->com.iridium126.createmanaindustry.client.render.scene.ScenePublicationPolicy.validate(candidate,8,38,72));
        assertNull(frame.publication());
        assertThrows(IllegalArgumentException.class,()->com.iridium126.createmanaindustry.client.render.scene.ScenePublicationPolicy.validate(candidate,9,37,72));
        assertThrows(IllegalArgumentException.class,()->com.iridium126.createmanaindustry.client.render.scene.ScenePublicationPolicy.validate(candidate,9,38,71));
        assertThrows(IllegalArgumentException.class,()->frame.candidate(9,0,2,3,4,5,9,-1,-1));
        com.iridium126.createmanaindustry.client.render.scene.ScenePublicationPolicy.validate(candidate,9,38,72);
        frame.publish(candidate);assertNotNull(frame.publication());
    }
    @Test void retiredCandidateCannotPublishEvenWhenBufferNamesAreReused() {
        var frame=new GpuFrameCoordinator();prepare(frame);var old=frame.candidate(1,1,2,3,4,5,1,-1,-1);
        frame.abort();prepare(frame);
        assertThrows(IllegalArgumentException.class,()->frame.publish(old));assertNull(frame.publication());
    }
}
