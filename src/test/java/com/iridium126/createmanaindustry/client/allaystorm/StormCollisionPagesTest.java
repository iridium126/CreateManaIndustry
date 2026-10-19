package com.iridium126.createmanaindustry.client.allaystorm;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.iridium126.createmanaindustry.client.allaystorm.StormCollisionPages.*;

class StormCollisionPagesTest {
    @Test void packedCellsPreserveAllAxesAndSharedPages(){
        int[] bits=new int[128];int count=0;
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)if((x+2*y+3*z)%7==0){int i=x|(z<<4)|(y<<8);bits[i>>>5]|=1<<(i&31);count++;}
        var page=page(1,bits);int found=0;
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){assertEquals((x+2*y+3*z)%7==0,page.solid(x,y,z));if(page.solid(x,y,z))found++;}
        assertEquals(count,found);assertEquals(AIR,page(2,new int[128]).kind());Arrays.fill(bits,-1);assertEquals(SOLID,page(3,bits).kind());
    }
    @Test void twentyThousandSectionsHaveNoCapacityOrHashHoles(){
        var rows=new HashMap<Section,Integer>();for(int i=0;i<20000;i++)rows.put(new Section(i%37-18,i/1369-100,i/37%37-18),i%3==0?AIR:i);
        int[] table=table(rows);rows.forEach((key,page)->assertEquals(page.intValue(),lookup(table,key)));
        assertEquals(UNKNOWN,lookup(table,new Section(10_000,-100_000,50_000)));
    }
    @Test void captureIsResumableAndDirtyPagesStayVisible(){
        var time=new AtomicLong();var cache=new StormCollisionCache(()->time.addAndGet(100));var key=new Section(-1,5000,-2);cache.demand(Set.of(key));
        var source=new StormCollisionCache.Source(){public boolean loaded(Section k){return true;}public Page snapshot(Section k,long revision){return null;}public boolean solid(Section k,int index){return index%2==0;}};
        cache.capture(source,250);assertNull(cache.get(key));
        for(int i=0;i<200;i++)cache.capture(source,500);assertNotNull(cache.get(key));var old=cache.get(key);assertEquals(0,cache.pending());
        cache.dirty(key);cache.capture(source,250);assertSame(old,cache.get(key));
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
