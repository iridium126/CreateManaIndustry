package com.iridium126.createmanaindustry.client.particles.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParticleEffectClockTest {
    @Test void displayRatesAndPauseUseWorldTime(){
        for(int fps:new int[]{30,60,144,240}){
            var clock=new ParticleEffectClock();var world=new Object();
            clock.sample(world,100,0,false);
            for(int frame=0;frame<=fps*2;frame++){
                double ticks=frame*20.0/fps;long whole=(long)ticks;
                assertEquals(frame/(float)fps,clock.sample(world,100+whole,(float)(ticks-whole),false),1e-6);
            }
            for(int frame=0;frame<fps;frame++)assertEquals(2,clock.sample(world,140,.9f,true),1e-6);
            assertEquals(2.025f,clock.sample(world,140,.5f,false),1e-6);
            assertEquals(2.025f,clock.sample(world,140,Float.NaN,false),1e-6);
            assertEquals(0,clock.sample(new Object(),900000,0,false));
        }
    }
    @Test void variableFramesDoNotAccumulateWallClockDrift(){
        var clock=new ParticleEffectClock();var world=new Object();clock.sample(world,0,0,false);
        for(float partial:new float[]{0,.02f,.87f,.91f,.99f})assertEquals(partial*.05f,clock.sample(world,0,partial,false),1e-7);
        assertEquals(.05f,clock.sample(world,1,0,false),1e-7);
        assertEquals(0,clock.sample(null,0,0,false));
    }
}
