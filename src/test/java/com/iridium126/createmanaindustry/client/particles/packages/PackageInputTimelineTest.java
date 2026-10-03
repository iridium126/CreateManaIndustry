package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PackageInputTimelineTest {
    @Test void vanillaTwentyClientTicksSupplyTwoHundredGameStepsDespiteWorldTimeCorrections(){
        for(int fps:new int[]{5,30,60,200}){
            var inputs=new PackageInputTimeline();var clock=new PackageSimulationClock();long captures=0;
            for(int frame=0;frame<=fps*20;frame++){
                long now=frame*1_000_000_000L/fps,clientTicks=now*20/1_000_000_000L;
                while(captures<clientTicks){captures++;var interval=inputs.advance(200);assertEquals(10,interval.size());}
                // Vanilla world gameTime can jump forward/backward on a time packet.
                // It is intentionally absent from both capture identity and consumption.
                long available=inputs.current().last();clock.sample(now,available,false,200,10);
                int submitted=0;for(int n=0;n<clock.stepsPerFrame()&&clock.due(available);n++){clock.commit(clock.nextTick());submitted++;}
                if(fps==200&&frame>=10)assertEquals(1,submitted,"Accelerated physics must not drain a capture then freeze for nine frames");
                assertFalse(clock.historyGap(),"FPS "+fps);
            }
            assertTrue(4000-clock.step()<=40,"Startup delay must remain bounded to one observed frame");
            clock.sample(20_200_000_000L,inputs.current().last(),false,200,10);
            for(int n=0;n<40&&clock.due(inputs.current().last());n++)clock.commit(clock.nextTick());
            assertEquals(4000,clock.step());assertEquals(4000,inputs.current().last());
        }
    }
    @Test void fractionalRateAndRateSwitchDoNotDropOrRepeatIntervals(){
        var inputs=new PackageInputTimeline();assertEquals(1,inputs.advance(20).last());
        for(int n=0;n<20;n++)inputs.advance(201);assertEquals(202,inputs.current().last());
        var down=inputs.advance(20);assertEquals(203,down.first());assertEquals(203,down.last());
        for(int n=0;n<20;n++)inputs.advance(10);assertEquals(223,inputs.current().last());
    }
    @Test void vanillaClientTargetUsesAtLeastFiftyMilliseconds()throws Exception{
        var type=PackageCollisionHookContractTest.type("net/minecraft/client/Minecraft");
        var target=type.methods.stream().filter(method->method.name.equals("getTickTargetMillis")).findFirst().orElseThrow();
        boolean maximum=false;for(var instruction:target.instructions)
            if(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call&&call.owner.equals("java/lang/Math")&&call.name.equals("max")&&call.desc.equals("(FF)F"))maximum=true;
        assertTrue(maximum,"Recheck vanilla capture cadence if its tick scheduling changes");
    }
}
