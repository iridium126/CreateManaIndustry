package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageCollisionUploadScheduleTest {
    static class Work implements PackageCollisionUploadSchedule.Work {
        final long[] copied=new long[3];final int[] pending={1_000_000,1_000_000,1_000_000};
        final List<PackageCollisionUploadSchedule.Atlas> calls=new ArrayList<>();final AtomicLong clock=new AtomicLong();
        long cost;
        public long uploaded(PackageCollisionUploadSchedule.Atlas atlas){return copied[atlas.ordinal()];}
        public void upload(PackageCollisionUploadSchedule.Atlas atlas,int bytes,long nanos){
            calls.add(atlas);if(nanos==0)return;int i=atlas.ordinal(),count=Math.min(pending[i],bytes);copied[i]+=count;pending[i]-=count;clock.addAndGet(cost);
        }
    }
    @Test void lightPriorityCannotSpendAllBytesBeforeCollisionGetsItsReservedOpportunity(){
        for(boolean movingFirst:new boolean[]{false,true}) {
            var work=new Work();PackageCollisionUploadSchedule.pump(work,movingFirst,true,true,262144,1000,work.clock::get);
            assertTrue(work.copied[0]>=65568);assertTrue(work.copied[1]>=65568);assertTrue(work.copied[2]>=16384);
            assertEquals(262144,Arrays.stream(work.copied).sum());assertEquals(PackageCollisionUploadSchedule.Atlas.LIGHT,work.calls.getFirst());
        }
    }
    @Test void unusedQuotasReturnToRemainingWorkWithoutExceedingTimeOrBytes(){
        var work=new Work();work.pending[0]=48;work.pending[2]=0;
        PackageCollisionUploadSchedule.pump(work,false,false,true,262144,1000,work.clock::get);
        assertEquals(48,work.copied[0]);assertEquals(262144-48,work.copied[1]);assertEquals(0,work.copied[2]);
        assertEquals(262144,Arrays.stream(work.copied).sum());
    }
    @Test void exhaustedSoftTimeNeverStartsAnotherNonzeroCopy(){
        var work=new Work();work.cost=1001;
        PackageCollisionUploadSchedule.pump(work,false,false,true,262144,1000,work.clock::get);
        assertEquals(81920,work.copied[0]);assertEquals(0,work.copied[1]);assertEquals(0,work.copied[2]);
    }
    @Test void zeroBudgetAllowsMetadataOnlyPublicationAndAbsentLightIsNeverCalled(){
        var work=new Work();PackageCollisionUploadSchedule.pump(work,true,true,false,0,0,work.clock::get);
        assertEquals(List.of(PackageCollisionUploadSchedule.Atlas.MOVING,PackageCollisionUploadSchedule.Atlas.WORLD),work.calls);
        assertEquals(0,Arrays.stream(work.copied).sum());
    }
}
