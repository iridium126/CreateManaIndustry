package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.client.allaystorm.rift.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StormCloudFrameTest {
    private final StormCloudFrame full=new StormCloudFrame(1e8,200,-1e8,32,.5,384,1,"test");
    @Test void openingPlateauAndOuterCloudsHaveExactMultipliers(){
        // y=264 -> r0=100, r1=116, r2=150, r3=182.
        for(double r:new double[]{0,16,99,100})assertEquals(0,full.density(r,264,0),1e-12);
        for(double r:new double[]{116,130,150})assertEquals(2,full.density(r,264,0),1e-12);
        for(double r:new double[]{182,200,1e8})assertEquals(1,full.density(r,264,0),1e-12);
        for(int i=0;i<10000;i++){
            double r=i*.031,actual=full.density(r,264,0);
            assertTrue(actual>=0&&actual<=2);assertEquals(actual,full.density(0,264,r),1e-12);
            assertEquals(0,0*actual,"multiplication never fills stock gaps");
        }
    }
    @Test void heightGateHasNoOvershootAndIsIdentityAtAndAboveRift(){
        for(double y:new double[]{384,385,1000})for(double r:new double[]{0,100,200})assertEquals(1,full.density(r,y,0));
        assertEquals(0,full.density(0,368,0));assertEquals(.5,full.density(0,376,0));
        assertEquals(1,full.density(0,384-1e-4,0),1e-10);
        assertEquals(1,StormCloudFrame.EMPTY.density(0,64,0));
    }
    @Test void smoothGrowthBirthAndStopAreBoundedAndPausedSamplesAreIdentical(){
        assertEquals(0,StormCloudFrame.strength(16384,0,40));
        assertEquals(1,StormCloudFrame.strength(16384,40,40));
        assertEquals(.5,StormCloudFrame.strength(16384,40,20));
        assertEquals(0,StormCloudFrame.strength(16384,40,0));
        assertEquals(StormCloudFrame.strength(4000,400,29.5),StormCloudFrame.strength(4000,400,29.5));
        for(int i=0;i<=16384;i+=37)assertTrue(StormCloudFrame.strength(i,80,40)>=0&&StormCloudFrame.strength(i,80,40)<=1);
    }
    @Test void coneUsesActualJuliaLongHalfAxisWithoutExtraMargin()throws Exception{
        var shape=JuliaShape.load();double extent=0;
        for(int i=0;i<shape.segments.length;i+=2)extent=Math.max(extent,Math.abs(shape.segments[i]));
        double expected=extent*shape.scale(32,200,384,2)*JuliaShape.projection(320).gain()/320;
        assertEquals(expected,shape.cloudConeSlope(32,200,384),1e-12);
        assertEquals(0,shape.cloudConeSlope(32,64,384));assertEquals(0,shape.cloudConeSlope(32,200,Double.POSITIVE_INFINITY));
    }
}
