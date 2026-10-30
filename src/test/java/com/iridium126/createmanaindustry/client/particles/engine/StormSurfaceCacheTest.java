package com.iridium126.createmanaindustry.client.particles.engine;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class StormSurfaceCacheTest {
    static ByteBuffer request(StormSurfaceCache cache,int... columns){
        var data=ByteBuffer.allocate(StormSurfaceCache.FEEDBACK_BYTES).order(ByteOrder.nativeOrder());
        for(int t=cache.active.nextSetBit(0);t>=0;t=cache.active.nextSetBit(t+1))data.putInt(t*16,cache.tileX[t]).putInt(t*16+4,cache.tileZ[t]).putInt(t*16+8,cache.versions[t]).putInt(t*16+12,cache.top);
        for(int c:columns){int b=4096+(c>>>5)*4;data.putInt(b,data.getInt(b)|(1<<(c&31)));}return data;
    }
    static StormSurfaceCache.Source plane(int y){return new StormSurfaceCache.Source(){
        public int minY(){return -64;}public int startY(int x,int z,int top){return Math.min(top,y+1);}public int occupied(int x,int v,int z){return v<=y?1:0;}
    };}
    static StormSurfaceCache cache(){var c=new StormSurfaceCache();c.configure(0,128,0,32);return c;}
    @Test void feedbackDeduplicatesAndFinishedColumnsAreNotRecaptured(){
        var c=cache();int cell=StormSurfaceCache.cell(0,0);var f=request(c,cell);c.feedback(c.epoch,f);c.feedback(c.epoch,f);assertEquals(1,c.queued());
        c.capture(plane(63),Long.MAX_VALUE);assertEquals(2,c.queries);assertEquals(1,c.completed);assertFalse(c.pending());
        c.feedback(c.epoch,f);assertFalse(c.pending());var out=ByteBuffer.allocate(16384);assertEquals(1,c.writePatches(out,1024));assertEquals(cell,out.getInt());assertEquals(64,StormSurfaceCache.height(out.getInt()));
        c.feedback(c.epoch,f);assertFalse(c.pending(),"Delayed missing feedback cannot rescan a completed column");
        long queries=c.queries;c.capture(plane(63),Long.MAX_VALUE);assertEquals(queries,c.queries);
    }
    @Test void unknownWaitsForNotificationAndOldVersionsCannotWakeIt(){
        var c=cache();int cell=0;var old=request(c,cell);c.feedback(c.epoch,old);
        c.capture(new StormSurfaceCache.Source(){public int minY(){return -64;}public int startY(int x,int z,int top){return top;}public int occupied(int x,int y,int z){return -1;}},Long.MAX_VALUE);
        assertEquals(1,c.blocked());assertFalse(c.pending());c.feedback(c.epoch,old);assertFalse(c.pending());
        c.changed(0,0,true);c.feedback(c.epoch,old);assertFalse(c.pending());c.feedback(c.epoch,request(c,cell));assertTrue(c.pending());
        long epoch=c.epoch;c.reset();c.configure(0,128,0,32);c.feedback(epoch,request(c,cell));assertFalse(c.pending());
    }
    @Test void zeroBudgetAndProbeLimitPreserveScanProgress(){
        var c=cache();c.feedback(c.epoch,request(c,0));c.capture(plane(0),0);assertEquals(0,c.queries);
        var deep=new StormSurfaceCache.Source(){public int minY(){return -5000;}public int startY(int x,int z,int top){return top;}public int occupied(int x,int y,int z){return y<=-4500?1:0;}};
        c.capture(deep,Long.MAX_VALUE);assertEquals(4096,c.queries);assertTrue(c.pending());c.capture(deep,Long.MAX_VALUE);assertFalse(c.pending());assertEquals(1,c.completed);
        var out=ByteBuffer.allocate(16);c.writePatches(out,1);assertEquals(-4499,StormSurfaceCache.height(out.getInt(4)));
    }
    @Test void clockBudgetYieldsWithoutRestartingTheColumn(){
        var clock=new AtomicLong();var c=new StormSurfaceCache(()->clock.getAndAdd(10));c.configure(0,128,0,32);c.feedback(c.epoch,request(c,0));
        c.capture(plane(0),20);assertTrue(c.pending());long first=c.queries;c.capture(plane(0),1000);assertTrue(c.queries>first);assertFalse(c.pending());
    }
    @Test void columnLimitAndUnsubmittedPatchesRetainPendingWork(){
        var c=cache();int[] columns=new int[300];for(int i=0;i<columns.length;i++)columns[i]=StormSurfaceCache.cell(i%20,i/20);
        c.feedback(c.epoch,request(c,columns));c.capture(plane(63),Long.MAX_VALUE);
        assertEquals(256,c.completed);assertEquals(44,c.queued());assertEquals(512,c.queries);
        var out=ByteBuffer.allocate(32);assertEquals(2,c.appendPatches(out,2));assertTrue(c.hasPatches());
        // A frame that cannot submit must leave exactly the same patch available.
        int first=out.getInt(0);out.clear();assertEquals(2,c.appendPatches(out,2));assertEquals(first,out.getInt(0));
        c.acceptPatches(out,0,2);out.clear();assertEquals(2,c.appendPatches(out,2));assertNotEquals(first,out.getInt(0));
        c.capture(plane(63),Long.MAX_VALUE);assertEquals(300,c.completed);assertFalse(c.pending());
    }
    @Test void emptyAndVoxelTakeoverCancelRepeatedWork(){
        var c=cache();var allAir=new StormSurfaceCache.Source(){public int minY(){return 0;}public int startY(int x,int z,int top){return 1;}public int occupied(int x,int y,int z){return 0;}};
        c.feedback(c.epoch,request(c,0));c.capture(allAir,Long.MAX_VALUE);var out=ByteBuffer.allocate(16);c.writePatches(out,1);assertEquals(StormSurfaceCache.EMPTY,out.getInt(4));
        c.feedback(c.epoch,request(c,1));assertTrue(c.pending());c.feedback(c.epoch,request(c));assertFalse(c.pending());
    }
    @Test void ringPreservesOverlapAndRejectsAliasedOldTiles(){
        var c=cache();int t=StormSurfaceCache.tile(0,0),version=c.versions[t];var old=request(c,0);c.dirty.clear();
        c.configure(16,128,0,32);assertEquals(version,c.versions[t]);assertFalse(c.dirty.get(t));
        c.configure(256,128,0,32);c.feedback(c.epoch,old);assertFalse(c.pending());assertNotEquals(0,c.tileX[t]);
        int now=c.versions[t];c.configure(256,129,0,32);assertNotEquals(now,c.versions[t]);
    }
    @Test void fullHeightRangeAndSourcePriorityFitOneWord(){
        for(int y:new int[]{-30_000_000,-1,0,30_000_000})for(int source:new int[]{StormSurfaceCache.CPU,StormSurfaceCache.VOXEL})assertEquals(y,StormSurfaceCache.height(StormSurfaceCache.encode(y,source)));
        assertTrue(Integer.compareUnsigned(StormSurfaceCache.encode(-30_000_000,StormSurfaceCache.VOXEL),StormSurfaceCache.encode(30_000_000,StormSurfaceCache.CPU))>0);
        assertEquals(Integer.MIN_VALUE,StormSurfaceCache.height(0));assertEquals(Integer.MIN_VALUE,StormSurfaceCache.height(1));
    }
}
