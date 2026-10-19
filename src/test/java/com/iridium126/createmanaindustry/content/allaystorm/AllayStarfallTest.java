package com.iridium126.createmanaindustry.content.allaystorm;
import org.junit.jupiter.api.Test;
import java.util.BitSet;
import static org.junit.jupiter.api.Assertions.*;
class AllayStarfallTest {
 @Test void availableGateCountsTheUnionOnlyInsideGeneratedMembers(){
  BitSet dead=new BitSet(),reserved=new BitSet();dead.set(0,10);reserved.set(5,15);dead.set(1000);reserved.set(1001);
  assertEquals(49,AllayStarfall.available(64,dead,reserved));assertEquals(11,dead.cardinality());assertEquals(11,reserved.cardinality());
 }
 @Test void populationThresholds(){assertEquals(0,AllayStarfall.columns(0,4096,16));assertEquals(1,AllayStarfall.columns(1,4096,16));assertEquals(1,AllayStarfall.columns(8191,4096,16));assertEquals(2,AllayStarfall.columns(8192,4096,16));assertEquals(16,AllayStarfall.columns(65536,4096,16));assertEquals(16,AllayStarfall.columns(131072,4096,16));assertEquals(32,AllayStarfall.columns(131072,4096,32));}
 @Test void exactExclusiveRosters(){BitSet dead=new BitSet(),reserved=new BitSet();for(int i=0;i<65536;i+=7)dead.set(i);
  for(int wave=0;wave<4;wave++){var chosen=AllayStarfall.select(65536,dead,reserved,16,512,wave);assertEquals(8192,chosen.members().length);assertEquals(16,chosen.columns());for(int m:chosen.members()){assertFalse(dead.get(m));assertFalse(reserved.get(m));reserved.set(m);}}
 }
 @Test void partialAndEmpty(){var a=AllayStarfall.select(65,new BitSet(),new BitSet(),32,64,23);assertEquals(2,a.columns());assertEquals(65,a.members().length);BitSet occupied=new BitSet();occupied.set(0,65);assertEquals(0,AllayStarfall.select(65,new BitSet(),occupied,32,512,23).columns());}
 @Test void quotaAndPermutationCeiling(){var a=AllayStarfall.select(131072,new BitSet(),new BitSet(),32,4096,1);BitSet seen=new BitSet();for(int m:a.members()){assertFalse(seen.get(m));seen.set(m);}assertEquals(131072,seen.cardinality());}
 @Test void identityPacking(){for(int slot=0;slot<4;slot++)for(int column=0;column<32;column++)for(int rank:new int[]{0,1,63,64,511,4095}){int p=AllayStarfall.pack(slot,column,rank);assertEquals(slot,AllayStarfall.slot(p));assertEquals(column,AllayStarfall.column(p));assertEquals(rank,AllayStarfall.rank(p));}}
 @Test void timingsAndClockWrap(){assertTrue(AllayStarfall.gatherSeconds(32)>=32/6f);assertTrue(AllayStarfall.pathSeconds(new float[]{0,0,0,0,60,0})>=10);assertEquals(.1f,AllayStarfall.elapsed(.05f,104857.55f),.01);assertEquals(0,AllayStarfall.offsetX(0,11));assertEquals(0,AllayStarfall.offsetZ(0,11));}
 @Test void delayedContactUsesSamplePosition(){var trail=new AllayStarfall.PlayerTrail();for(long t=0;t<50;t++)trail.record(t,t,0,0);assertEquals(0,trail.distanceSquared(20,20,0,0));assertTrue(trail.distanceSquared(20,49,0,0)>3);assertTrue(Double.isInfinite(trail.distanceSquared(0,0,0,0)));}
 @Test void restartedStormAtSameTickHasDifferentEpoch(){assertNotEquals(AllayStarfall.epoch(400,1),AllayStarfall.epoch(400,2));assertEquals(AllayStarfall.epoch(400,1),AllayStarfall.epoch(400,1));}
}
