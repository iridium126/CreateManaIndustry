package com.iridium126.createmanaindustry.content.allaystorm;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StormCenterTimelineTest {
    private final StormIdentity id=new StormIdentity(ResourceLocation.withDefaultNamespace("overworld"),7);
    private StormCenterSample point(double x,int count,long tick){return new StormCenterSample(x,128,30_000_000.125,count,tick);}
    @Test void centerAndSummaryArrivalOrderProduceTheSameDelayedFrame() {
        var a=new StormCenterTimeline();var b=new StormCenterTimeline();
        var p=point(29_000_000.125,16384,100);var c=point(29_000_002.125,32768,120);
        a.offer(id,p);a.offer(id,c);b.offer(id,c);b.offer(id,p,c);
        assertEquals(a.sample(130,.5f),b.sample(130,.5f));
        assertEquals(29_000_001.175,a.sample(130,.5f).x(),1e-8);
        assertEquals(1.2*a.sample(130,.5f).radius(),a.sample(130,.5f).envelopeRadius());
        assertFalse(a.offer(id,c));assertFalse(a.offer(id,point(100,1,110)));
        assertEquals(c.x(),a.sample(1000,0).x());
    }
    @Test void metadataDeliveryDoesNotAdvanceSamplesAndRetiredEpochCannotReturn() {
        var t=new StormCenterTimeline();var p=point(2,16384,100);var c=point(4,16390,120);
        t.offer(id,p,c);var frame=t.sample(130,.25f);
        t.offer(id,p,c);assertEquals(frame,t.sample(130,.25f));assertSame(p,t.previous());
        var next=new StormIdentity(id.dimension(),8);assertTrue(t.offer(next,point(10,20,140)));
        assertFalse(t.offer(id,point(20,30,160)));assertEquals(next,t.identity());
    }
    @Test void firstSampleClampsAndAbsoluteTicksDoNotWrap() {
        var t=new StormCenterTimeline();long tick=1L<<45;
        t.offer(id,point(.125,1,tick));assertEquals(.125,t.sample(tick,0).x());
        t.offer(id,point(2.125,9,tick+20));assertEquals(1.125,t.sample(tick+30,0).x());
        assertEquals(t.sample(tick+30,0),t.sample(tick+30,0));
    }
}
