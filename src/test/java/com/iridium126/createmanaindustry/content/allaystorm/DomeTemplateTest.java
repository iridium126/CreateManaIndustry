package com.iridium126.createmanaindustry.content.allaystorm;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DomeTemplateTest {
 @Test void publishedResourceMatchesItsFinalArcClockSeparationCertificate()throws Exception{
  try(var in=DomeTemplate.class.getResourceAsStream("/data/createmanaindustry/dome-template-v4.bin");var proof=DomeTemplate.class.getResourceAsStream("/data/createmanaindustry/dome-template-v4.validation.json")){
   var certificate=com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(proof,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
   assertEquals(certificate.get("sha256").getAsString(),java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())));
   assertTrue(certificate.get("continuousMinimumBodySpacing").getAsDouble()>=.50);assertEquals(768,certificate.get("coreChannels").getAsInt());assertTrue(certificate.get("belowPlaneChannels").getAsInt()<=768);
   assertTrue(certificate.get("minimumInwardSpeedAtHalfSecond").getAsDouble()>=3);assertTrue(certificate.get("continuousMaximumAcceleration").getAsDouble()<=18);assertTrue(certificate.get("continuousMaximumJerk").getAsDouble()<=120);assertTrue(certificate.get("continuousTurnRateBound").getAsDouble()<=Math.PI);
   assertTrue(certificate.get("minimumArcAfterClosest").getAsDouble()>=2.999);
  }
 }
 @Test void twelveCurtainsRetainCoreChannelsAndPolarCoverage(){
  int[] total=new int[12],core=new int[12];double top=0;int polar=0;
  for(int rank=0;rank<DomeTemplate.COUNT;rank++){
   int curtain=DomeTemplate.curtain(rank);total[curtain]++;if(DomeTemplate.coreChannel(rank))core[curtain]++;
   var p=DomeTemplate.gatherPoint(rank,0);assertTrue(p.length()>=16&&p.length()<=32);top=Math.max(top,p.y);if(p.y>31.5&&Math.hypot(p.x,p.z)<3)polar++;
  }
  for(int count:total)assertEquals(1024,count);for(int count:core)assertEquals(64,count);assertTrue(top>=31.5);assertTrue(polar>=12);
 }
 @Test void cyclicPhaseMappingIsBijectionAndHandoffHasNoPositionOrVelocityJump(){
  for(double lock:new double[]{0,.013,7.7,31,104857.5}){
   BitSet seen=new BitSet();
   for(int rank=0;rank<DomeTemplate.COUNT;rank++){
    int mapped=DomeTemplate.mappedRank(rank,lock);assertFalse(seen.get(mapped));seen.set(mapped);
    assertEquals(DomeTemplate.ringBase(rank),DomeTemplate.ringBase(mapped));
    assertTrue(DomeTemplate.gatherPoint(rank,lock).distanceTo(DomeTemplate.lockedPoint(rank,0,lock))<.0001);
   }
   assertEquals(12288,seen.cardinality());
   for(int rank:new int[]{0,30,4096,8192,12287}){
    double dt=.00001;var p=DomeTemplate.lockedPoint(rank,0,lock);var next=DomeTemplate.lockedPoint(rank,dt,lock);
    assertTrue(next.subtract(p).scale(1/dt).distanceTo(DomeTemplate.gatherVelocity(rank,lock))<.025);
   }
  }
 }
 @Test void stationaryCenterIsCrossedAndRoutesDoNotEndAtSixMetres(){
  int crossings=0;
  for(int rank=0;rank<DomeTemplate.COUNT;rank++)if(DomeTemplate.coreChannel(rank)){
   double closest=100,closestBody=Double.POSITIVE_INFINITY;double duration=DomeTemplate.duration(rank),closestAt=0;
   for(double t=0;t<duration;t+=.05){var p=DomeTemplate.point(rank,DomeTemplate.distanceAt(t,DomeTemplate.gatherSpeed(rank)));closest=Math.min(closest,Math.hypot(p.x,p.z));double d=p.lengthSqr();if(d<closestBody){closestBody=d;closestAt=t;}}
   assertTrue(closest<=1.08,"Core channel missed center: "+rank+" radius="+closest);assertTrue((duration-closestAt)*6>=2.7,"Channel "+rank+" omitted its three-dimensional exit: "+duration+" closest="+closestAt);crossings++;
  }assertEquals(768,crossings);
 }
 @Test void peripheralCurtainsAlsoReachTheEightMetreCenterRegion(){
  for(int rank=0;rank<DomeTemplate.COUNT;rank+=31)if(!DomeTemplate.coreChannel(rank)){
   double closest=Double.POSITIVE_INFINITY;
   for(double t=0;t<DomeTemplate.duration(rank);t+=.1)closest=Math.min(closest,DomeTemplate.point(rank,DomeTemplate.distanceAt(t,DomeTemplate.gatherSpeed(rank))).length());
   assertTrue(closest<=8.2,"Peripheral channel remained overhead: "+rank+" distance="+closest);
  }
 }
 @Test void streamlinesUseStableSpeedsBetweenFourAndSix(){
  for(int rank:new int[]{0,111,4096,8192,12287})for(double t:new double[]{0,1,10,100}){
   double dt=.0001;assertTrue(DomeTemplate.gatherSpeed(rank)>=4&&DomeTemplate.gatherSpeed(rank)<=6);assertEquals(DomeTemplate.gatherSpeed(rank),DomeTemplate.gatherVelocity(rank,t).length(),.0002);
   assertEquals(DomeTemplate.gatherSpeed(rank),DomeTemplate.gatherPoint(rank,t+dt).distanceTo(DomeTemplate.gatherPoint(rank,t))/dt,.0003);
  }
 }
 @Test void thickShellTemplateIsFixedFiniteAndExitsBeyondCore(){
  assertTrue(DomeTemplate.available());Map<String,List<net.minecraft.world.phys.Vec3>> grid=new HashMap<>();double spacing=1.4;
  for(int i=0;i<12288;i++){var p=DomeTemplate.point(i,0);assertTrue(p.y>=0&&p.length()>=16&&p.length()<=32);int x=(int)Math.floor(p.x/spacing),y=(int)Math.floor(p.y/spacing),z=(int)Math.floor(p.z/spacing);
   for(int a=-1;a<=1;a++)for(int b=-1;b<=1;b++)for(int c=-1;c<=1;c++)for(var q:grid.getOrDefault((x+a)+","+(y+b)+","+(z+c),List.of()))assertTrue(p.distanceTo(q)>=0.50);
   grid.computeIfAbsent(x+","+y+","+z,k->new ArrayList<>()).add(p);assertTrue(DomeTemplate.duration(i)>0&&DomeTemplate.duration(i)<=60);assertTrue(Double.isFinite(DomeTemplate.point(i,360).length()));
   for(double time:new double[]{0,1,5,10,20,40,59.9})if(time<DomeTemplate.duration(i)){var q=DomeTemplate.point(i,time*6);assertTrue(Double.isFinite(q.x)&&q.y>=-20&&Double.isFinite(q.z));}


  }
 }
}
