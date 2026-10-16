package com.iridium126.createmanaindustry.client.greattrick;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SharedFatePriorityTest {
    @Test void boundaryNoiseDoesNotExchangeBindings() {
        int band=SharedFatePriority.band(-1,3.9);
        for(double d:new double[]{4.01,3.99,4.2,3.8,4.7})assertEquals(band,SharedFatePriority.band(band,d));
        assertEquals(2,SharedFatePriority.band(band,4.8));
    }
    @Test void enteringANearerBandAlsoUsesHysteresis() {
        assertEquals(2,SharedFatePriority.band(2,3.5));
        assertEquals(1,SharedFatePriority.band(2,3.2));
        assertEquals(0,SharedFatePriority.band(2,0));
    }
    @Test void largeMovesImmediatelyChooseTheNewBand() {
        assertEquals(50,SharedFatePriority.band(1,100));
        assertEquals(0,SharedFatePriority.band(-1,0));
    }
}
