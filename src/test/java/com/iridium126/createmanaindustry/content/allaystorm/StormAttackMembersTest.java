package com.iridium126.createmanaindustry.content.allaystorm;
import org.junit.jupiter.api.Test;
import java.util.BitSet;
import static org.junit.jupiter.api.Assertions.*;
class StormAttackMembersTest {
 @Test void availableGateCountsTheUnionOnlyInsideGeneratedMembers(){
  BitSet dead=new BitSet(),reserved=new BitSet();dead.set(0,10);reserved.set(5,15);dead.set(1000);reserved.set(1001);
  assertEquals(49,StormAttackMembers.available(64,dead,reserved));assertEquals(11,dead.cardinality());assertEquals(11,reserved.cardinality());
 }
 @Test void identityPacking(){for(int slot=0;slot<4;slot++)for(int column=0;column<32;column++)for(int rank:new int[]{0,1,63,64,511,4095}){int p=StormAttackMembers.pack(slot,column,rank);assertEquals(slot,StormAttackMembers.slot(p));assertEquals(column,StormAttackMembers.column(p));assertEquals(rank,StormAttackMembers.rank(p));}}
 @Test void sharedClockWrapsAtTwentyOneBits(){assertEquals(.1f,StormAttackMembers.elapsed(.05f,104857.55f),.01);assertEquals(0,StormAttackMembers.elapsed(0,0),.001);}
 @Test void restartedStormAtSameTickHasDifferentEpoch(){assertNotEquals(StormAttackMembers.epoch(400,1),StormAttackMembers.epoch(400,2));assertEquals(StormAttackMembers.epoch(400,1),StormAttackMembers.epoch(400,1));}
}
