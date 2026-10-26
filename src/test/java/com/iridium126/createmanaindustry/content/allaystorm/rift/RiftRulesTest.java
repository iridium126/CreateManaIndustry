package com.iridium126.createmanaindustry.content.allaystorm.rift;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RiftRulesTest {
    boolean eligible(int count,double x,double y,double z) { return RiftRules.eligible(true,true,count,true,x,y,z,0,0,320); }
    @Test void strictFeetAndHorizontalBoundariesUseGeneratedCount() {
        assertFalse(eligible(16383,0,385,0));
        assertTrue(eligible(16384,0,Math.nextUp(384d),0));
        assertFalse(eligible(16384,0,384,0));
        assertFalse(eligible(16384,64,385,0));
        assertTrue(eligible(16384,Math.nextDown(64d),385,0));
        assertFalse(eligible(16384,0,385,Double.NaN));
        assertFalse(RiftRules.eligible(false,true,16384,true,0,385,0,0,0,320));
        assertFalse(RiftRules.eligible(true,false,16384,true,0,385,0,0,0,320));
        assertFalse(RiftRules.eligible(true,true,16384,false,0,385,0,0,0,320));
    }
    @Test void distantSubscriptionIgnoresAltitudeAndUsesHysteresis() {
        assertTrue(RiftRules.subscribed(false,512,0,0,0));
        assertFalse(RiftRules.subscribed(false,Math.nextUp(512d),0,0,0));
        assertTrue(RiftRules.subscribed(true,576,0,0,0));
        assertFalse(RiftRules.subscribed(true,Math.nextUp(576d),0,0,0));
    }
    @Test void nativeBandRequiresWholeOccupancy() {
        assertTrue(RiftRules.nativeBand(-128,384));
        assertFalse(RiftRules.nativeBand(Math.nextDown(-128d),10));
        assertFalse(RiftRules.nativeBand(0,Math.nextUp(384d)));
        assertFalse(RiftRules.nativeBand(Double.NaN,30));
    }
    @Test void landingOffsetsAreStableCircularAndStartAtTheRequestedColumn() {
        var offsets=RiftLandingOffsets.OFFSETS;
        assertEquals(new RiftLandingOffsets.Offset(0,0),offsets.getFirst());
        assertEquals(3209,offsets.size());
        assertEquals(offsets.size(),new java.util.HashSet<>(offsets).size());
        for(int i=1;i<offsets.size();i++) assertTrue(offsets.get(i-1).distanceSquared()<=offsets.get(i).distanceSquared());
        assertEquals(java.util.List.of(new RiftLandingOffsets.Offset(0,0),new RiftLandingOffsets.Offset(-1,0),new RiftLandingOffsets.Offset(0,-1),new RiftLandingOffsets.Offset(0,1),new RiftLandingOffsets.Offset(1,0)),RiftLandingOffsets.generate(1));
    }
}
