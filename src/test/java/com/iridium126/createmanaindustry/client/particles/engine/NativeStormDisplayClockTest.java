package com.iridium126.createmanaindustry.client.particles.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class NativeStormDisplayClockTest {
    @Test void consecutiveSamplesAndRepeatedPublicationsPlayAtTwoTicksOfDelay(){
        var clock=new NativeStormDisplayClock();
        for(int tick=10;tick<=12;tick++)
            clock.commit(tick,tick,tick>10,clock.transition(tick,tick,tick>10));
        assertEquals(new NativeStormDisplayClock.Weights(.5f,.5f,0,0),clock.sample(12.5,1,false));
        assertEquals(new NativeStormDisplayClock.Weights(.25f,.75f,0,0),clock.sample(12.75,12_500_001,false));
        clock.commit(13,13,true,clock.transition(13,13,true));
        assertEquals(new NativeStormDisplayClock.Weights(1,0,0,0),clock.sample(13,25_000_001,false));
    }

    @Test void gapAnchorsTheLastDrawAndPauseKeepsTheRecoveryWeight(){
        var clock=new NativeStormDisplayClock();
        clock.commit(10,10,false,clock.transition(10,10,false));
        var shown=clock.sample(13,1,false);
        assertEquals(NativeStormDisplayClock.Weights.CURRENT,shown);
        var gap=clock.transition(14,14,true);
        assertTrue(gap.newAnchor());assertEquals(shown,gap.source());
        clock.commit(14,14,true,gap);
        assertEquals(1,clock.sample(14,50_000_001,false).anchor());
        assertEquals(.5f,clock.sample(15,100_000_001,false).anchor());
        var frozen=clock.sample(16,150_000_001,true);
        assertEquals(frozen,clock.sample(20,500_000_001,true));
        assertEquals(.5f,clock.sample(16,550_000_001,false).anchor());
        assertEquals(0,clock.sample(17,600_000_001,false).anchor());
    }

    @Test void backwardWorldCorrectionDoesNotRewindPublishedPositions(){
        var clock=new NativeStormDisplayClock();
        clock.commit(10,100,false,clock.transition(10,100,false));
        var shown=clock.sample(101.25,1,false);
        assertEquals(shown,clock.sample(98.5,12_500_001,false));
        var correction=clock.transition(11,99,true);
        assertTrue(correction.newAnchor());assertEquals(shown,correction.source());
        clock.commit(11,99,true,correction);
        assertEquals(1,clock.sample(99,25_000_001,false).anchor());
        clock.commit(12,100,false,clock.transition(12,100,false));
        assertEquals(0,clock.sample(100,50_000_001,false).anchor());
    }

    @Test void playbackIsIndependentOfDisplayRate(){
        for(int fps:new int[]{30,60,144,240}){
            var clock=new NativeStormDisplayClock();
            int sequence=100;
            clock.commit(sequence,sequence,false,clock.transition(sequence,sequence,false));
            for(int frame=0;frame<3*fps;frame++){
                double world=100+20.0*frame/fps;
                while(sequence<(int)world){
                    sequence++;
                    clock.commit(sequence,sequence,true,clock.transition(sequence,sequence,true));
                }
                var weights=clock.sample(world,1+1_000_000_000L*frame/fps,false);
                if(world<102)continue;
                double rendered=(sequence-2)*weights.oldest()+(sequence-1)*weights.previous()+sequence*weights.current();
                assertEquals(world-2,rendered,2e-5,"render rate "+fps);
            }
        }
    }
}
