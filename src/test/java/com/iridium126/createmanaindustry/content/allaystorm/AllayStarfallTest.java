package com.iridium126.createmanaindustry.content.allaystorm;
import org.junit.jupiter.api.Test;
import java.util.BitSet;
import static org.junit.jupiter.api.Assertions.*;
class AllayStarfallTest {
 @Test void availableGateCountsTheUnionOnlyInsideGeneratedMembers(){
  BitSet dead=new BitSet(),reserved=new BitSet();dead.set(0,10);reserved.set(5,15);dead.set(1000);reserved.set(1001);
  assertEquals(49,AllayStarfall.available(64,dead,reserved));assertEquals(11,dead.cardinality());assertEquals(11,reserved.cardinality());
 }
 @Test void identityPacking(){for(int slot=0;slot<4;slot++)for(int column=0;column<32;column++)for(int rank:new int[]{0,1,63,64,511,4095}){int p=AllayStarfall.pack(slot,column,rank);assertEquals(slot,AllayStarfall.slot(p));assertEquals(column,AllayStarfall.column(p));assertEquals(rank,AllayStarfall.rank(p));}}
 @Test void timingsAndClockWrap(){assertTrue(AllayStarfall.gatherSeconds(32)>=32/6f);assertTrue(AllayStarfall.pathSeconds(new float[]{0,0,0,0,60,0})>=10);assertEquals(.1f,AllayStarfall.elapsed(.05f,104857.55f),.01);assertEquals(0,AllayStarfall.offsetX(0,11));assertEquals(0,AllayStarfall.offsetZ(0,11));}
 @Test void restartedStormAtSameTickHasDifferentEpoch(){assertNotEquals(AllayStarfall.epoch(400,1),AllayStarfall.epoch(400,2));assertEquals(AllayStarfall.epoch(400,1),AllayStarfall.epoch(400,1));}
}
