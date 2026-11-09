package com.iridium126.createmanaindustry.client.allaystorm;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.iridium126.createmanaindustry.client.allaystorm.StormCollisionPages.*;

class StormCollisionPagesTest {
    @Test void signedRegularGridsStayBoundedAtCapacity(){
        for(int[] dimensions:new int[][]{{32,8,32},{40,12,40},{32,32,32}}){
            var rows=new LinkedHashMap<Section,Integer>();
            for(int y=0;y<dimensions[1];y++)for(int z=0;z<dimensions[2];z++)for(int x=0;x<dimensions[0];x++)
                rows.put(new Section(x-dimensions[0]/2,y+5000,z-dimensions[2]/2),((x+y+z)&1)==0?AIR:SOLID);
            var index=table(rows);
            assertTrue(index.primaryRows()<=MAX_ROWS);assertTrue(index.words().length<=(MAX_ROWS+MAX_SECTIONS)*4);
            rows.forEach((key,value)->assertEquals(value.intValue(),lookup(index,key)));
        }
    }
    @Test void adversarialCollisionsUseExactSignedSortedOverflow(){
        var rows=new LinkedHashMap<Section,Integer>();
        for(int x=-10000;rows.size()<12;x++)if((hash(x,-5000,-3)&31)==0)rows.put(new Section(x,-5000,-3),rows.size()%2==0?AIR:SOLID);
        var index=table(rows);assertEquals(32,index.primaryRows());assertEquals(4,index.overflow());
        rows.forEach((key,value)->assertEquals(value.intValue(),lookup(index,key)));
        assertEquals(UNKNOWN,lookup(index,new Section(Integer.MIN_VALUE,-5000,-3)));
        var tooMany=new HashMap<Section,Integer>();for(int i=0;i<=MAX_SECTIONS;i++)tooMany.put(new Section(i,0,0),AIR);
        assertThrows(IllegalStateException.class,()->table(tooMany));
    }
    @Test void consumersKeepIndependentImmutableRevisionDeltas(){
        var cache=new StormCollisionCache();var a=cache.cursor();var b=cache.cursor();
        var key=new Section(-3,5000,9);
        cache.demand(Set.of(key));assertTrue(a.poll().reset());
        cache.capture(new StormCollisionCache.Source(){public boolean loaded(Section k){return true;}public Page snapshot(Section k,long revision){return new Page(revision,AIR,null);}public boolean solid(Section k,int i){throw new AssertionError();}},Long.MAX_VALUE);
        var first=a.poll();assertEquals(AIR,first.entries().getFirst().page().kind());assertNull(a.poll());
        cache.dirty(key);var removed=a.poll();assertNull(removed.entries().getFirst().page());assertEquals(first.revision(),removed.baseRevision());
        assertEquals(AIR,first.entries().getFirst().page().kind(),"a later withdrawal cannot mutate an accepted tick");
        var late=b.poll();assertTrue(late.reset());assertTrue(late.entries().isEmpty());
        cache.demand(Set.of());assertEquals(key,a.poll().entries().getFirst().section());
        assertEquals(key,b.poll().entries().getFirst().section());
        assertTrue(new StormCollisionCache().cursor().poll().reset());
    }
    @Test void packedCellsPreserveAllAxesAndSharedPages(){
        int[] bits=new int[128];int count=0;
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)if((x+2*y+3*z)%7==0){int i=x|(z<<4)|(y<<8);bits[i>>>5]|=1<<(i&31);count++;}
        var page=page(1,bits);int found=0;
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){assertEquals((x+2*y+3*z)%7==0,page.solid(x,y,z));if(page.solid(x,y,z))found++;}
        assertEquals(count,found);assertEquals(AIR,page(2,new int[128]).kind());Arrays.fill(bits,-1);assertEquals(SOLID,page(3,bits).kind());
    }
    @Test void twentyThousandSectionsHaveNoCapacityOrHashHoles(){
        var rows=new HashMap<Section,Integer>();for(int i=0;i<20000;i++)rows.put(new Section(i%37-18,i/1369-100,i/37%37-18),i%3==0?AIR:i);
        var table=table(rows);rows.forEach((key,page)->assertEquals(page.intValue(),lookup(table,key)));
        assertEquals(UNKNOWN,lookup(table,new Section(10_000,-100_000,50_000)));
    }
    @Test void captureIsResumableAndDirtyPagesAreUnknownUntilRecaptured(){
        var time=new AtomicLong();var cache=new StormCollisionCache(()->time.addAndGet(100));var key=new Section(-1,5000,-2);cache.demand(Set.of(key));
        var source=new StormCollisionCache.Source(){public boolean loaded(Section k){return true;}public Page snapshot(Section k,long revision){return null;}public boolean solid(Section k,int index){return index%2==0;}};
        cache.capture(source,250);assertNull(cache.get(key));
        for(int i=0;i<200;i++)cache.capture(source,500);assertNotNull(cache.get(key));var old=cache.get(key);assertEquals(0,cache.pending());
        long version=cache.version();
        cache.dirty(key);cache.capture(source,250);
        assertNull(cache.get(key));
        assertFalse(cache.ready().containsKey(key),"Dirty geometry must leave the publication before recapture");
        assertTrue(cache.version()>version);
        for(int i=0;i<200;i++)cache.capture(source,500);assertNotEquals(old.revision(),cache.get(key).revision());
        cache.unavailable(key);assertNull(cache.get(key));
    }
    @Test void reuseDoesNotReadWorldAndUnknownNeverBecomesAir(){
        var cache=new StormCollisionCache();var loaded=new Section(0,0,0);var unknown=new Section(0,1,0);cache.demand(Set.of(loaded,unknown));
        cache.capture(new StormCollisionCache.Source(){public boolean loaded(Section k){return k.equals(loaded);}public Page snapshot(Section k,long revision){return new Page(revision,AIR,null);}public boolean solid(Section k,int i){fail("Shared snapshot read world");return false;}},1_000_000);
        assertEquals(AIR,cache.get(loaded).kind());assertNull(cache.get(unknown));assertEquals(1,cache.ready().size());
        long version=cache.version();cache.demand(Set.of());assertEquals(0,cache.requested());assertTrue(cache.version()>version);
    }
}
