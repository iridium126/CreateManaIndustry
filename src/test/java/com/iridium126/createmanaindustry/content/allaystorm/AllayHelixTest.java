package com.iridium126.createmanaindustry.content.allaystorm;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class AllayHelixTest {
 static AllayHelix.Selection roster(int count){return new AllayHelix.Selection(0,3,java.util.stream.IntStream.range(0,count).toArray(),new int[]{count/2,count});}
 static float[] straight(int count){float l=AllayHelix.length(count);return new float[]{-24-l,4,0,-24,4,0,-12,4,0,0,4,0,12,4,0,12+l,4,0};}
 static AllayHelix.Flight flight(int n){return AllayHelix.flight(roster(n),straight(n),12,10,15);}
 @Test void deadlineIsOneSecondAfterTailForEverySchedule(){
  for(float launch:new float[]{0,20,104857.5f})for(float exit:new float[]{64,80,128}){
   var flight=AllayHelix.linearFlight(roster(4096),net.minecraft.world.phys.Vec3.ZERO,new net.minecraft.world.phys.Vec3(0,-1,0),exit,launch);
   assertEquals(12,flight.speed());assertEquals(32,flight.maxSpeed());
   assertEquals(1,AllayHelix.deadline(flight)-flight.finish(),.001);
   var scheduled=flight.schedule(launch+2,launch+3);assertEquals(1,AllayHelix.deadline(scheduled)-scheduled.finish(),.001);
  }
 }
 @Test void probabilitiesAndLimits(){
  assertEquals(StormAttack.HELIX,StormAttack.choose(.199,.2,.2));assertEquals(StormAttack.DOME,StormAttack.choose(.2,.2,.2));assertEquals(StormAttack.STARFALL,StormAttack.choose(.4,.2,.2));
  assertEquals(StormAttack.HELIX,StormAttack.choose(.49,.8,.8));assertEquals(StormAttack.DOME,StormAttack.choose(.51,.8,.8));
  assertEquals(0,StormAttack.quota(131072,0,8192));assertEquals(8192,StormAttack.quota(131072,.1,8192));assertFalse(StormAttack.targetRequired(StormAttack.HELIX,true));
 }
 @Test void actualOppositeArmsExactAndExclusive(){
  BitSet dead=new BitSet(),reserved=new BitSet();dead.set(0,123);
  for(int seed:new int[]{1,2,16777215})for(int wave=0;wave<4;wave++){
   var selection=AllayHelix.select(131072,dead,reserved,8192,seed,wave);assertEquals(4096,selection.members().length);assertEquals(selection.armA()+3,selection.armB());
   int first=selection.ends()[0];assertTrue(first==2048);
   for(int rank=0;rank<4096;rank++){int member=selection.members()[rank];assertFalse(dead.get(member));assertFalse(reserved.get(member));reserved.set(member);assertEquals(rank<first?selection.armA():selection.armB(),AllayHelix.arm(seed,member));}
  }
 }
 @Test void shortageAndOtherPairs(){
  var dead=new BitSet();var reserved=new BitSet();for(int i=0;i<131072;i++)if(AllayHelix.arm(1,i)==0||AllayHelix.arm(1,i)==3)reserved.set(i);
  var selected=AllayHelix.select(131072,dead,reserved,8192,1,0);assertEquals(4096,selected.members().length);assertNotEquals(0,selected.armA());
  reserved.set(0,131072);assertEquals(0,AllayHelix.select(131072,dead,reserved,8192,1,0).members().length);assertEquals(0,AllayHelix.select(511,dead,new BitSet(),8192,1,0).members().length);
 }
 @Test void arcTableSpeedAndTailExit(){
  for(int count:new int[]{512,2048,8192,16384}){var f=flight(count);float length=f.arcLength();assertEquals(24+12+2*f.length(),length,.02);
   for(int i=1;i<48;i++)assertTrue(f.samples()[i*4+3]>f.samples()[(i-1)*4+3]);
   assertEquals(f.length(),AllayHelix.progress(f,14));assertEquals(length,AllayHelix.progress(f,f.finish()+1),.001);
   assertEquals(0,AllayHelix.speedAt(f,14));assertEquals(f.speed(),AllayHelix.speedAt(f,16),.001);assertEquals(f.speed()*.5f,AllayHelix.speedAt(f,15.25f),.001);assertEquals(0,AllayHelix.speedAt(f,f.finish()));
   assertTrue(length-f.length()>=f.targetArc()+11.8);float previous=AllayHelix.progress(f,15);
   for(float t=15.01f;t<f.finish();t+=.01f){float next=AllayHelix.progress(f,t);assertTrue(next>=previous-.001);assertTrue((next-previous)/.01f<=12.01);previous=next;}
   assertEquals(2,AllayHelix.phase(11,4,13,false,f));assertEquals(3,AllayHelix.phase(14,4,13,true,f));assertEquals(4,AllayHelix.phase(15,4,13,true,f));assertEquals(5,AllayHelix.phase(f.finish(),4,13,true,f));
   assertEquals(0,AllayHelix.distanceSquared(f,0,4,0),.001);assertTrue(AllayHelix.distanceSquared(f,0,40,0)>100);
  }
 }
 @Test void splineRejectsUnsafeGeometryAndTipDensity(){
  assertThrows(IllegalArgumentException.class,()->AllayHelix.flight(roster(4096),new float[]{0,0,0,0,0,0},12,10,15));
  assertThrows(IllegalArgumentException.class,()->AllayHelix.flight(roster(512),new float[]{0,0,0,1500,0,0},12,10,15));
  assertEquals(.2,AllayHelix.memberU(.3f),.00001);assertEquals(1,AllayHelix.memberU(1),.00001);
  assertTrue(AllayHelix.memberU(.15f)<.1);assertEquals(64,AllayHelix.length(512));assertEquals(64,AllayHelix.length(16384));
 }
 @Test void directAttackHasFortyFiveDegreeAxisAndNoPostGatherWait(){
  var f=AllayHelix.attackFlight(roster(8192),-24,88,0,0,12,20);
  assertEquals(20,f.thrust());assertEquals(20,f.charge());
  for(int i=1;i<6;i++){double dx=f.controls()[i*3]-f.controls()[(i-1)*3],dy=f.controls()[i*3+1]-f.controls()[(i-1)*3+1];assertEquals(-dx,dy,.0001);}
  assertEquals(4,AllayHelix.phase(20,20,20,true,f));assertEquals(32,AllayHelix.radius(16384));
 }
 @Test void cachedScatterHasGaussianDensityRatherThanAnEmptyStrand(){
  double sum=0,x=0,y=0;Set<String> unique=new HashSet<>();
  for(int i=0;i<16000;i++){float[] p=AllayHelix.scatter(123,i);assertArrayEquals(p,AllayHelix.scatter(123,i));double r=Math.hypot(p[0],p[1]);assertTrue(r>0&&r<=3.00001);sum+=r*r;x+=p[0];y+=p[1];unique.add(p[0]+","+p[1]);}
  assertEquals(16000,unique.size());assertEquals(0,x/16000,.03);assertEquals(0,y/16000,.03);assertTrue(sum/16000>1.8&&sum/16000<2.0);
 }
 @Test void completeEnvelopeAndDynamicObstruction(){var f=flight(8192);assertTrue(AllayHelix.clearTube(f,0,f.arcLength(),(x,y,z,r)->true));
  assertFalse(AllayHelix.clearTube(f,0,f.arcLength(),(x,y,z,r)->Math.abs(x)>r));
  float arc=AllayHelix.progress(f,16),ahead=arc+f.speed();float[] point=new float[3];AllayHelix.point(f.controls(),AllayHelix.parameter(f,ahead-1),point,0);float wall=point[0];
  assertFalse(AllayHelix.clearTube(f,arc-f.length(),ahead,(x,y,z,r)->Math.abs(x-wall)>r));
  assertTrue(AllayHelix.clearTube(f,0,1,(x,y,z,r)->Math.abs(x-wall)>r));
 }
 @Test void exactThresholdsAndRealOppositeArms(){var dead=new BitSet();var reserved=new BitSet();assertEquals(0,AllayHelix.select(16383,dead,reserved,4096,123,0).members().length);var a=AllayHelix.select(16384,dead,reserved,4096,123,0);assertEquals(4096,a.members().length);assertArrayEquals(new int[]{2048,4096},a.ends());dead.set(16383);assertEquals(0,AllayHelix.select(16384,dead,reserved,4096,123,0).members().length);
  dead.clear();int[] available=new int[6];for(int i=0;i<131072;i++){int arm=AllayHelix.arm(123,i);if((arm==0||arm==3)&&available[arm]++<2048)continue;reserved.set(i);}assertEquals(4096,AllayHelix.select(131072,dead,reserved,4096,123,0).members().length);
  for(int i=0;i<131072;i++)if(!reserved.get(i)&&AllayHelix.arm(123,i)==3){reserved.set(i);break;}assertEquals(0,AllayHelix.select(131072,dead,reserved,4096,123,0).members().length);
 }
 @Test void fixedTemplateEnvelopeAndMeshClearance(){assertTrue(HelixTemplate.available());for(int i=0;i<4096;i++){double x=HelixTemplate.coordinate(i,0),y=HelixTemplate.coordinate(i,1),z=HelixTemplate.coordinate(i,2),u=(32-x)/64;assertTrue(u>=0&&u<=1);assertTrue(Math.hypot(y,z)<=.6*Math.pow(32/.6,u)+.0001);for(int j=0;j<i;j++){double a=x-HelixTemplate.coordinate(j,0),b=y-HelixTemplate.coordinate(j,1),c=z-HelixTemplate.coordinate(j,2);assertTrue(a*a+b*b+c*c>=1.95999);}}}
 @Test void threeDimensionalAxisAndBoundedTurn(){var v=new net.minecraft.world.phys.Vec3(0,-1,0);var r=new AllayHelix.Selection(0,3,new int[4096],new int[]{2048,4096});var f=AllayHelix.linearFlight(r,new net.minecraft.world.phys.Vec3(500,200,-900),v,100,30);assertEquals(1,AllayHelix.axis(f).dot(v),.00001);assertEquals(30,f.thrust());var t=AllayHelix.turn(v,new net.minecraft.world.phys.Vec3(0,1,0),Math.toRadians(2));assertEquals(Math.toRadians(2),Math.acos(v.dot(t)),.000001);}
 @Test void farCoordinateFlightKeepsUnitAxis(){var axis=new net.minecraft.world.phys.Vec3(.2,-.7,.4).normalize();var roster=new AllayHelix.Selection(0,3,new int[4096],new int[]{2048,4096});var flight=AllayHelix.linearFlight(roster,new net.minecraft.world.phys.Vec3(-28000000,100,29000000),axis,100,30);assertEquals(1,axis.dot(AllayHelix.axis(flight)),.0000001);}
}
