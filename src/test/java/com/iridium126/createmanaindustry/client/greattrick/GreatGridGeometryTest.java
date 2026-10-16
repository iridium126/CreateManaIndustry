package com.iridium126.createmanaindustry.client.greattrick;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GreatGridGeometryTest {
    @Test void defaultSnapsOnlyAfterSeventyFivePercentOfAnAdjacentStep() {
        assertEquals(-1,GreatGridGeometry.snap(0.749,0,0,0,2,0.75));
        assertEquals(10,GreatGridGeometry.snap(0.75,0,0,0,2,0.75));
        assertEquals(10,GreatGridGeometry.snap(1,0,0,0,2,0.75));
        assertEquals(10,GreatGridGeometry.snap(0.75,0,0,0,2,Double.NaN));
    }

    @Test void thresholdCanBeChangedWithoutChangingGridSpacing() {
        assertEquals(-1,GreatGridGeometry.snap(0.749,0,0,0,2,0.75));
        assertEquals(10,GreatGridGeometry.snap(0.75,0,0,0,2,0.75));
        assertEquals(-1,GreatGridGeometry.snap(0.99,0,0,0,2,1));
        assertEquals(10,GreatGridGeometry.snap(1,0,0,0,2,1));
    }

    @Test void snappingIsIndependentOfGuiScaleAndUsesEuclideanDistance() {
        for (double radius : new double[]{0.25,9.6,80,1000}) {
            double spacing = GreatGridGeometry.spacing(radius);
            double centerX = 37, centerY = -19;
            double dx = GreatGridGeometry.x(5,centerX,radius)-centerX;
            double dy = GreatGridGeometry.y(5,centerY,radius)-centerY;
            assertEquals(spacing,Math.hypot(dx,dy),spacing*1e-12);
            assertEquals(-1,GreatGridGeometry.snap(centerX+0.899*dx,centerY+0.899*dy,centerX,centerY,radius,0.9));
            assertEquals(5,GreatGridGeometry.snap(centerX+0.9*dx,centerY+0.9*dy,centerX,centerY,radius,0.9));
            assertEquals(-1,GreatGridGeometry.snap(centerX+dx+0.08*spacing,centerY+dy+0.08*spacing,
                    centerX,centerY,radius,0.9),"A diagonal offset outside the 10% circle must not snap");
        }
    }

    @Test void eachRenderedDotIsAlsoAnExactSnapTarget() {
        for (int i=0;i<19;i++) {
            assertEquals(i,GreatGridGeometry.snap(GreatGridGeometry.x(i,24,9.6),GreatGridGeometry.y(i,24,9.6),
                    24,24,9.6,0.9));
        }
        assertEquals(-1,GreatGridGeometry.snap(Double.NaN,0,0,0,2,0.9));
        assertEquals(-1,GreatGridGeometry.snap(0,0,0,0,0,0.9));
    }
}
