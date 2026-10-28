package com.iridium126.createmanaindustry.content.allaystorm;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StormCenterTimelineTest {
    private final StormIdentity id=new StormIdentity(ResourceLocation.withDefaultNamespace("overworld"),7);
    private StormCenterSample point(double x,int count,long tick){return new StormCenterSample(x,128,30_000_000.125,count,tick);}
    @Test void smallerHashedEpochStartsImmediatelyAndOldPacketsCannotReviveIt(){
        long created=1;while(AllayStarfall.epoch(created+1,17)>AllayStarfall.epoch(created,17))created++;
        var old=new StormIdentity(id.dimension(),AllayStarfall.epoch(created,17));
        var next=new StormIdentity(id.dimension(),AllayStarfall.epoch(created+1,17));
        assertTrue(next.epoch()<old.epoch());
        var t=new StormCenterTimeline();t.offer(old,point(1,16383,100));
        assertTrue(t.select(next));assertTrue(t.offer(next,point(10,20000,120)));
        assertFalse(t.offer(old,point(20,32768,160)));assertEquals(20000,t.sample(120,0).count());
        t.offer(next,point(12,20020,140));
        assertTrue(t.sample(150,.75f).count()>t.sample(150,.25f).count());
        var previous=t.previous();assertTrue(t.select(next));assertSame(previous,t.previous());
        t.retire(next);assertFalse(t.offer(next,point(0,1,200)));
        var stopped=new StormCenterTimeline();var last=point(10,20000,140);stopped.offer(next,last);stopped.retire(next);
        stopped.offer(next,point(9,19980,120),last);assertSame(last,stopped.previous());
        assertEquals(20000,stopped.sample(200,0).count());
        t.clear();assertTrue(t.offer(next,point(0,1,200)));
    }
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
