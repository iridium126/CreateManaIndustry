package com.iridium126.createmanaindustry.content.allaystorm;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AllayDomeTest {
 @Test void probabilityAndQuotaBoundaries(){
  assertFalse(AllayDome.choose(0,0));assertTrue(AllayDome.choose(.999,1));
  assertTrue(AllayDome.choose(.199999,.2));assertFalse(AllayDome.choose(.2,.2));
  assertEquals(0,AllayDome.quota(1000,0,16384));assertEquals(767,AllayDome.quota(3839,.2,16384));
  assertEquals(768,AllayDome.quota(3840,.2,16384));assertEquals(16384,AllayDome.quota(131072,.2,16384));
  assertEquals(24576,AllayDome.quota(131072,1,24576));
 }
 @Test void sevenGroupsPreserveEveryMember(){
  for(int count:new int[]{768,769,4096,8192,16384,24576}){
   int[] ends=AllayDome.groups(count);assertEquals(7,ends.length);assertEquals(count,ends[6]);
   assertEquals(count/10,count-ends[5]);int least=Integer.MAX_VALUE,most=0,first=0;
   for(int g=0;g<6;g++){int n=ends[g]-first;least=Math.min(least,n);most=Math.max(most,n);first=ends[g];}
   assertTrue(most-least<=1);for(int i=0;i<count;i++)assertTrue(AllayDome.group(ends,i)>=0);
   assertEquals(-1,AllayDome.group(ends,count));
   assertTrue(AllayDome.radius(count)>=8&&AllayDome.radius(count)<=20);
  }
 }
 @Test void sharedReservationsDeathsAndUndersize(){
  BitSet dead=new BitSet(),reserved=new BitSet();dead.set(0,20);
  int[] a=AllayDome.select(10000,dead,reserved,2000,42);assertEquals(2000,a.length);
  for(int member:a){assertFalse(dead.get(member));assertFalse(reserved.get(member));reserved.set(member);}
  int[] b=AllayStarfall.select(10000,dead,reserved,4,512,55).members();assertEquals(2048,b.length);
  for(int member:b){assertFalse(reserved.get(member));reserved.set(member);}
  int[] c=AllayDome.select(10000,dead,reserved,10000,56);assertEquals(10000-20-a.length-b.length,c.length);
  reserved.set(0,10000);assertEquals(0,AllayDome.select(10000,dead,reserved,768,0).length);
  for(int member:a)reserved.clear(member);assertEquals(2000,AllayDome.select(10000,dead,reserved,3000,0).length);
 }
 @Test void onePetalHarmAndCrownNeverHarm(){
  for(float radius:new float[]{8,12,20})for(float t=0;t<65;t+=.025f){
   int active=0;for(int group=0;group<7;group++)if(AllayDome.memberPhase(t,0,radius,group)==4){active++;assertEquals(group,AllayDome.closingPetal(t,0,radius));}
   assertTrue(active<=1);assertNotEquals(4,AllayDome.memberPhase(t,0,radius,6));
  }
  assertEquals(-1,AllayDome.closingPetal(1.499f,0,20));assertEquals(0,AllayDome.closingPetal(1.5f,0,20));
 }
 @Test void exitsRejectHolesStepsAndBlockedLanes(){
  var flat=new AllayDome.Terrain(){public double floor(double x,double z){return 0;}public boolean clear(double x,double y,double z){return true;}};
  assertTrue(AllayDome.hasExit(flat,0,0,0,20,0));
  for(int type=0;type<3;type++){
   final int mode=type;
   var unsafe=new AllayDome.Terrain(){public double floor(double x,double z){return Math.hypot(x,z)>4?(mode==0?-10:mode==1?3:0):0;}public boolean clear(double x,double y,double z){return mode!=2||Math.hypot(x,z)<4;}};
   assertFalse(AllayDome.hasExit(unsafe,0,0,0,20,0));
  }
  var single=new AllayDome.Terrain(){public double floor(double x,double z){return 0;}public boolean clear(double x,double y,double z){return Math.hypot(x,z)<2||x>0&&z>0;}};
  assertTrue(AllayDome.hasExit(single,0,0,0,8,0));
 }
 @Test void sameMemberIndependentVictimsAndWaves(){
  var contacts=new AllayDome.Contacts();UUID a=UUID.randomUUID(),b=UUID.randomUUID();
  assertTrue(contacts.add(a,123));assertFalse(contacts.add(a,123));assertTrue(contacts.add(b,123));assertFalse(contacts.add(b,123));
  assertTrue(new AllayDome.Contacts().add(a,123));
 }
 @Test void lockedAreaOutlivesOriginalTarget(){
  assertTrue(AllayDome.targetRequired(AllayDome.DOME,false));
  assertFalse(AllayDome.targetRequired(AllayDome.DOME,true));
  assertTrue(AllayDome.targetRequired(AllayDome.STARFALL,false));
  assertTrue(AllayDome.targetRequired(AllayDome.STARFALL,true));
 }
 @Test void observerActivationFollowsLockedRegion(){
  assertEquals(0,AllayDome.distanceSquared(10,64,0,0,64,0,20));
  assertEquals(100,AllayDome.distanceSquared(30,64,0,0,64,0,20));
  assertEquals(25,AllayDome.distanceSquared(0,97,0,0,64,0,20));
  assertTrue(AllayDome.distanceSquared(139,64,0,0,64,0,20)<=120*120);
  assertTrue(AllayDome.distanceSquared(141,64,0,0,64,0,20)>120*120);
 }
}
