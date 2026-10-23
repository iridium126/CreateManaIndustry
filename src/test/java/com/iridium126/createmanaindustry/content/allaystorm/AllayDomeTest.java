package com.iridium126.createmanaindustry.content.allaystorm;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AllayDomeTest {
 @Test void gatheringBudgetsFlightInterceptionTurnStreakAndFeedback(){
  for(double distance:new double[]{0,12,40,96,300})for(double radius:new double[]{16,32,64}){
   double expected=Math.max(8,(distance+1.15*radius+32)/8+32./2+16./18+Math.PI/Math.min(Math.PI,18./8)+.75+1);
   float actual=AllayDome.gatherSeconds(distance,radius);assertEquals((float)(Math.ceil(expected*20)/20),actual);assertTrue(actual>=expected-.00001);assertEquals(Math.round(actual*20),actual*20,.001);
  }
  assertEquals(31.35f,AllayDome.gatherSeconds(40,16));assertThrows(IllegalArgumentException.class,()->AllayDome.gatherSeconds(Double.NaN,16));
 }
 @Test void fixedCountThresholdAndIdentityGroups(){
  assertEquals(0,AllayDome.quota(16383));assertEquals(12288,AllayDome.quota(16384));
  assertArrayEquals(new int[]{12288},AllayDome.groups(12288));assertEquals(0,AllayDome.group(new int[]{12288},12287));assertEquals(-1,AllayDome.group(new int[]{12288},12288));assertEquals(32,AllayDome.radius(12288));
 }
 @Test void sharedReservationsDeathsAndUndersize(){
  BitSet dead=new BitSet(),reserved=new BitSet();dead.set(0,20);
  int[] a=AllayDome.select(10000,dead,reserved,2000,42);assertEquals(2000,a.length);
  for(int member:a){assertFalse(dead.get(member));assertFalse(reserved.get(member));reserved.set(member);}
  int[] b=AllayStarfall.selectExact(10000,dead,reserved,64,55);assertEquals(64,b.length);
  for(int member:b){assertFalse(reserved.get(member));reserved.set(member);}
  int[] c=AllayDome.select(10000,dead,reserved,10000,56);assertEquals(10000-20-a.length-b.length,c.length);
  reserved.set(0,10000);assertEquals(0,AllayDome.select(10000,dead,reserved,768,0).length);
  for(int member:a)reserved.clear(member);assertEquals(2000,AllayDome.select(10000,dead,reserved,3000,0).length);
 }
 @Test void sameMemberIndependentVictimsAndWaves(){
  var contacts=new AllayDome.Contacts();UUID a=UUID.randomUUID(),b=UUID.randomUUID();
  assertTrue(contacts.add(a,123));assertFalse(contacts.add(a,123));assertTrue(contacts.add(b,123));assertFalse(contacts.add(b,123));
  assertTrue(new AllayDome.Contacts().add(a,123));
 }
 @Test void lockedAreaOutlivesOriginalTarget(){
  assertFalse(AllayDome.targetRequired(AllayDome.DOME,false));
  assertFalse(AllayDome.targetRequired(AllayDome.DOME,true));
  assertTrue(AllayDome.targetRequired(AllayDome.STARFALL,false));
  assertTrue(AllayDome.targetRequired(AllayDome.STARFALL,true));
 }
 @Test void observerActivationFollowsLockedRegion(){
  assertEquals(0,AllayDome.distanceSquared(10,64,0,0,64,0,20));
  assertEquals(100,AllayDome.distanceSquared(30,64,0,0,64,0,20));
  assertEquals(25,AllayDome.distanceSquared(0,101,0,0,64,0,20));
  assertTrue(AllayDome.distanceSquared(139,64,0,0,64,0,20)<=120*120);
  assertTrue(AllayDome.distanceSquared(141,64,0,0,64,0,20)>120*120);
 }
}
