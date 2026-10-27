package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.client.allaystorm.rift.JuliaShape;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JuliaProjectionMathTest {
    @Test void weakLensPreservesTheFullEntranceSphereCone() {
        for(double distance:new double[]{64,100,128,128.001,150,192,240,255.999,256,320,512,1000}) {
            var lens=JuliaShape.projection(distance);
            assertTrue(Float.isFinite(lens.weak())&&Float.isFinite(lens.gain()));
            assertTrue(lens.weak()>=0&&lens.weak()<=1&&lens.gain()>=1);
            if(distance>128) {
                double width=64*lens.gain(),depth=width*(1-lens.weak());
                double projectedCone=width/Math.sqrt(distance*distance-depth*depth);
                double originalCone=64/Math.sqrt(distance*distance-64*64);
                assertEquals(originalCone,projectedCone,1e-7,"The weak projection must not uncover the entry circle");
            }
        }
        assertEquals(new JuliaShape.ProjectionStyle(0,1),JuliaShape.projection(128));
        assertEquals(1,JuliaShape.projection(256).weak());
        assertEquals(JuliaShape.projection(128).gain(),JuliaShape.projection(128.001).gain(),1e-6);
        assertEquals(JuliaShape.projection(256).gain(),JuliaShape.projection(255.999).gain(),1e-6);
    }
    @Test void circleFitUsesTheFrontalSilhouetteAndKeepsTheSameAngularReference() {
        for(double radius:new double[]{2,16,32,64})for(double planeY:new double[]{320,384,448}) {
            double frontRadius=.7065225475591042;
            double scale=JuliaShape.referenceScale(radius,128,planeY,frontRadius);
            double footprint=scale*frontRadius*JuliaShape.projection(planeY-64).gain();
            double referenceCircle=1.2*radius*(planeY-64)/(128-64);
            assertEquals(referenceCircle*1.01,footprint,1e-9);
        }
    }
}
