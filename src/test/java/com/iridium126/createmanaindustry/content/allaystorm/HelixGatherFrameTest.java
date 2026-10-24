package com.iridium126.createmanaindustry.content.allaystorm;

import java.util.BitSet;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HelixGatherFrameTest {
    @Test void rigidReferenceHasAnalyticVelocityAndContinuousUpdates(){
        var frame=HelixGatherFrame.initial(new Vec3(10,80,-30),new Vec3(1,-1,0).normalize(),new Vec3(2,0,0));
        var local=new Vec3(-24,18,9);
        for(int step=0;step<80;step++){
            var next=frame.next(new Vec3(80*Math.cos(step*.05),0,80*Math.sin(step*.05)),new Vec3(2*Math.cos(step*.03),0,2*Math.sin(step*.03)));
            var end=frame.sample(frame.seconds()+.25);var start=next.sample(next.seconds());
            assertTrue(end.point(local).distanceTo(start.point(local))<1e-7);
            assertTrue(end.motion(local).distanceTo(start.motion(local))<1e-7);
            for(int k=1;k<25;k++){
                double time=next.seconds()+k*.01,h=1e-5;
                var sample=next.sample(time);var derivative=next.sample(time+h).point(local).subtract(next.sample(time-h).point(local)).scale(1/(2*h));
                assertTrue(sample.motion(local).distanceTo(derivative)<.0001);
                assertTrue(sample.omega().length()<=HelixGatherFrame.TURN+.00001);
            }
            frame=next;
        }
    }
    @Test void extrapolationStopsSmoothlyAndIsBounded(){
        var frame=HelixGatherFrame.initial(Vec3.ZERO,new Vec3(1,0,0),new Vec3(2,0,0)).next(new Vec3(0,0,100),new Vec3(2,0,0));
        var last=frame.sample(frame.seconds()+.5);var later=frame.sample(frame.seconds()+20);
        assertEquals(0,last.velocity().length(),1e-12);assertEquals(0,last.omega().length(),1e-12);
        assertTrue(last.center().distanceTo(later.center())<1e-9);
        assertTrue(last.axis().distanceTo(later.axis())<1e-9);
    }
    @Test void bothAttacksUseTheSameDynamicThresholdAndInclusiveDeadline(){
        for(int members:new int[]{4096,12288})for(int tick:new int[]{0,100,200}){
            int required=StormGatherRules.required(members,tick,200);var eligible=new BitSet();eligible.set(0,members);var ready=new BitSet();ready.set(0,required);
            assertTrue(StormGatherRules.valid(ready,eligible,members,tick/20f,0,10));
            assertEquals(DomeGatherRules.required(members,tick,200),required);
            ready.clear(required-1);assertFalse(StormGatherRules.valid(ready,eligible,members,tick/20f,0,10));
        }
        assertFalse(StormGatherRules.withinDeadline(10.05f,0,10));
        assertEquals(3687,StormGatherRules.required(4096,200,200));
    }
    @Test void packetRecoveryPreservesRigidPositionAndAnalyticVelocity(){
        var first=HelixGatherFrame.initial(Vec3.ZERO,new Vec3(1,0,0),new Vec3(2,0,0));var frames=new java.util.ArrayList<HelixGatherFrame>();frames.add(first);
        for(int i=0;i<8;i++)frames.add(frames.getLast().next(new Vec3(0,0,100),new Vec3(2,0,0)));
        float began=1.5f;var before=first.sample(began);var after=frames.get(6).sample(began);var recovery=HelixGatherRecovery.between(began,before,after);Vec3 local=new Vec3(24,8,-3);
        var initial=recovery.apply(after,began);assertTrue(initial.point(local).distanceTo(before.point(local))<1e-8);assertTrue(initial.motion(local).distanceTo(before.motion(local))<1e-8);
        for(double t=began+.001;t<began+.249;t+=.001){double epsilon=1e-5;var raw=frames.get(6);Vec3 derivative=recovery.apply(raw.sample(t+epsilon),t+epsilon).point(local).subtract(recovery.apply(raw.sample(t-epsilon),t-epsilon).point(local)).scale(.5/epsilon);assertTrue(derivative.distanceTo(recovery.apply(raw.sample(t),t).motion(local))<.0001);}
        var end=frames.get(7).sample(began+.25);var corrected=recovery.apply(end,began+.25);assertTrue(end.point(local).distanceTo(corrected.point(local))<1e-8);assertTrue(end.motion(local).distanceTo(corrected.motion(local))<1e-8);
    }
    @Test void referenceHistorySurvivesPacketsAndCopies(){
        var frame=HelixGatherFrame.initial(new Vec3(-24,4,0),new Vec3(1,0,0),Vec3.ZERO);
        var p=HelixPacketTest.packet(0,1,true).withGatherFrames(new HelixGatherFrame[]{frame,frame.next(new Vec3(0,0,40),Vec3.ZERO)});
        var q=HelixPacketTest.roundtrip(p);assertEquals(2,q.gatherFrames().length);
        assertEquals(2,q.withMembers(new int[0]).gatherFrames().length);
        assertTrue(q.gatherFrames()[1].sample(.4).axis().distanceTo(p.gatherFrames()[1].sample(.4).axis())<1e-6);
    }
    @Test void lockFreezesTheAcceptedTransformEvenWhenWorldClockLosesPrecision(){
        var frame=HelixGatherFrame.initial(new Vec3(12,80,20),new Vec3(1,0,0),new Vec3(2,0,0)).next(new Vec3(0,0,100),new Vec3(2,0,0));
        float time=.35f;var accepted=frame.sample(time);var frozen=HelixGatherFrame.frozen(frame.version(),time,accepted);var local=new Vec3(24,3,8);
        for(double delta:new double[]{-.008,0,.008,1}){var sample=frozen.sample(time+delta);assertTrue(sample.point(local).distanceTo(accepted.point(local))<1e-8);assertEquals(0,sample.motion(local).length(),1e-12);}
    }
}
