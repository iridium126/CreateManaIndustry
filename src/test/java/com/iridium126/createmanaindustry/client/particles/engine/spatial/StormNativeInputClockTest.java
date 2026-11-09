package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import com.iridium126.createmanaindustry.client.particles.engine.NativeStormDisplayClock;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StormNativeInputClockTest {
    private static void append(StormNativeInputs inputs,long world){
        inputs.journal().appendWorldSample(world,StormControlJournal.Parameters.inactive(1,0),List.of(),null,
                StormControlJournal.Target.ABSENT,0,List.of(),false,null,List.of(),false);
    }

    @Test void correctedWorldTimeDoesNotChangeInputPhaseOrDisplayVelocity(){
        for(int fps:new int[]{30,60,144,240}){
            var inputs=new StormNativeInputs();var clock=new NativeStormDisplayClock();
            var pending=new ArrayDeque<StormControlJournal.Tick>();
            int captured=0;long world=100000,accepted=0;
            for(int frame=0;frame<fps*12;frame++){
                double logical=20.0*frame/fps;
                while(captured<(int)logical){
                    captured++;
                    world+=captured%60==0?-1:captured%40==0?0:captured%20==0?3:1;
                    append(inputs,world);
                    var tick=inputs.journal().sendable();inputs.journal().sent(tick);pending.addLast(tick);
                }
                float partial=(float)(logical-(int)logical);
                double displayTime=inputs.displayTick(partial);
                assertEquals(logical,displayTime,1e-6,"render phase must follow captured client ticks");
                while(!pending.isEmpty()&&pending.getFirst().tick()+.1+.65*(1+Math.sin(pending.getFirst().tick()*.37))*.5<=logical){
                    var tick=pending.removeFirst();
                    assertTrue(tick.gameTime()>90000,"gameplay metadata keeps authoritative world time");
                    var edge=clock.transition(tick.sequence(),tick.tick(),accepted!=0);
                    if(accepted!=0){assertTrue(edge.continuous());assertFalse(edge.newAnchor());}
                    clock.commit(tick.sequence(),tick.tick(),accepted!=0,edge);accepted=tick.tick();
                    inputs.journal().receive(tick.sequence(),true);
                }
                if(accepted==0)continue;
                var w=clock.sample(displayTime,1+1_000_000_000L*frame/fps,false);
                if(logical<4)continue;
                double position=(accepted-2)*w.oldest()+(accepted-1)*w.previous()+accepted*w.current();
                assertEquals(logical-2,position,3e-5,"100ms motion at "+fps+" FPS");
                assertFalse(clock.transition(accepted+1,accepted+1,true).newAnchor());assertEquals(0,w.anchor());
            }
        }
    }

    @Test void inputClockAdvancesEvenWhenNativeWorkIsBacklogged(){
        var inputs=new StormNativeInputs();
        for(int i=0;i<8;i++)append(inputs,100); // Repeated server time, with no GPU receipt.
        assertEquals(8.5,inputs.displayTick(.5f));
        assertEquals(0,inputs.journal().committed());
        var clock=new NativeStormDisplayClock();clock.commit(1,1,false,clock.transition(1,1,false));
        clock.sample(inputs.displayTick(.5f),1,false);
        // Playback must still exhaust its real history, rather than slow down its clock to hide late work.
        var weights=clock.sample(inputs.displayTick(.75f),12_500_001,false);
        assertEquals(new NativeStormDisplayClock.Weights(0,0,1,0),weights);
        var resumed=clock.transition(9,9,true);assertTrue(resumed.newAnchor());
        assertEquals(8,inputs.displayTick(-1));assertEquals(9,inputs.displayTick(2));
    }
}
