package com.iridium126.createmanaindustry.content.allaystorm;

import java.util.BitSet;

/** Identity selection and the immutable arc-length flight shared with the GPU. */
public final class AllayHelix {
 public static final int MIN_MEMBERS=512,MAX_MEMBERS=16384,SAMPLES=48;
 public static final float WARNING=1.5f,CHARGE_MIN=3,CHARGE_MAX=8,RAMP=.5f;
 public static final int SWEEP_INVALID=0x80000000,PHASE_MASK=255;
 public record Selection(int armA,int armB,int[] members,int[] ends) {}
 public record Flight(int armA,int armB,float length,float radius,float maxSpeed,float speed,float charge,
   float thrust,float finish,float curvature,float targetArc,float[] controls,float[] samples) {
  public float arcLength(){return samples[samples.length-1];}
  public float duration(){return finish-thrust;}
  public Flight schedule(float charge,float thrust){float duration=(arcLength()-length)/speed+RAMP;return new Flight(armA,armB,length,radius,maxSpeed,speed,charge,thrust,thrust+duration,curvature,targetArc,controls,samples);}
 }
 private AllayHelix() {}
 public static int arm(int stormSeed,int member){float seed=AllayStormWaves.memberSeed(stormSeed,member);return Math.min(5,(int)Math.floor(AllayStormWaves.hash1(seed*5.7f)*6));}
 public static Selection select(int generated,BitSet dead,BitSet reserved,int quota,int stormSeed,int waveSeed){
  generated=Math.min(AllayStarfall.MAX_MEMBERS,Math.max(0,generated));quota=Math.min(MAX_MEMBERS,Math.max(0,quota));
  if(quota<MIN_MEMBERS||generated<MIN_MEMBERS)return new Selection(-1,-1,new int[0],new int[0]);
  int[][] available=new int[6][Math.min(generated,quota)];int[] counts=new int[6];
  int mask=AllayStarfall.MAX_MEMBERS-1,offset=AllayStarfall.mix(waveSeed)&mask,stride=(AllayStarfall.mix(waveSeed^0x619c731b)|1)&mask;
  for(int r=0;r<=mask;r++){int member=(offset+r*stride)&mask;if(member>=generated||dead.get(member)||reserved.get(member))continue;int a=arm(stormSeed,member);if(counts[a]<available[a].length)available[a][counts[a]++]=member;}
  int first=Math.floorMod(waveSeed,3);
  for(int p=0;p<3;p++){int a=(first+p)%3,b=a+3,total=Math.min(quota,counts[a]+counts[b]);
   if(total<MIN_MEMBERS||counts[a]<256||counts[b]<256)continue;
   int na=Math.min(counts[a],(total+1)/2),nb=Math.min(counts[b],total-na);na=total-nb;
   int[] members=new int[total];System.arraycopy(available[a],0,members,0,na);System.arraycopy(available[b],0,members,na,nb);
   return new Selection(a,b,members,new int[]{na,total});
  }return new Selection(-1,-1,new int[0],new int[0]);
 }
 public static float length(int members){return Math.max(16,Math.min(32,(float)Math.sqrt(members)/3));}
 public static float radius(int members){return Math.max(1.5f,Math.min(3,(float)Math.sqrt(members)/32));}
 public static float memberU(float fraction){return fraction<.3f?.2f*(float)Math.pow(fraction/.3f,1.5):.2f+.8f*(fraction-.3f)/.7f;}
 public static float progress(Flight f,float now){
  float elapsed=Math.max(0,Math.min(f.duration(),AllayStarfall.elapsed(now,f.thrust))),distance;
  if(elapsed<RAMP)distance=.5f*f.speed*elapsed*elapsed/RAMP;
  else if(elapsed>f.duration()-RAMP){float remaining=f.duration()-elapsed;distance=f.arcLength()-f.length-.5f*f.speed*remaining*remaining/RAMP;}
  else distance=f.speed*(elapsed-RAMP*.5f);
  return f.length+Math.max(0,Math.min(f.arcLength()-f.length,distance));
 }
 public static float speedAt(Flight f,float now){float elapsed=AllayStarfall.elapsed(now,f.thrust);if(elapsed<=0||elapsed>=f.duration())return 0;return f.speed*Math.min(1,Math.min(elapsed/RAMP,(f.duration()-elapsed)/RAMP));}
 public static float parameter(Flight f,float arc){
  if(arc<=0)return 0;if(arc>=f.arcLength())return 1;
  int low=0,high=SAMPLES-1;while(high-low>1){int mid=(low+high)/2;if(f.samples[mid*4+3]<arc)low=mid;else high=mid;}
  float start=f.samples[low*4+3],end=f.samples[high*4+3];return (low+(arc-start)/Math.max(end-start,1e-5f))/(SAMPLES-1);
 }
 public static void point(float[] controls,float parameter,float[] out,int offset){
  int count=controls.length/3;float t=Math.max(0,Math.min(1,parameter))*(count-1);int segment=Math.min(count-2,(int)t);float u=t-segment;
  for(int axis=0;axis<3;axis++){float p0=controls[Math.max(0,segment-1)*3+axis],p1=controls[segment*3+axis],p2=controls[(segment+1)*3+axis],p3=controls[Math.min(count-1,segment+2)*3+axis];
   float m1=segment==0?p2-p1:(p2-p0)*.5f,m2=segment==count-2?p2-p1:(p3-p1)*.5f;
   out[offset+axis]=(2*u*u*u-3*u*u+1)*p1+(u*u*u-2*u*u+u)*m1+(-2*u*u*u+3*u*u)*p2+(u*u*u-u*u)*m2;
  }
 }
 public static Flight flight(Selection roster,float[] controls,float maxSpeed,float charge,float thrust){
  if(controls.length<6||controls.length>18||controls.length%3!=0)throw new IllegalArgumentException("Helix control count");
  float[] points=new float[SAMPLES*4],dense=new float[3],previous=new float[3];double[] derivative=new double[3],second=new double[3];
  float total=0,curvature=0,targetArc=0;int denseSteps=1024;point(controls,0,previous,0);int next=0;
  for(int i=0;i<=denseSteps;i++){
   float t=i/(float)denseSteps;point(controls,t,dense,0);
   float dx=dense[0]-previous[0],dy=dense[1]-previous[1],dz=dense[2]-previous[2],distance=(float)Math.sqrt(dx*dx+dy*dy+dz*dz);
   if(i>0&&distance<1e-7)throw new IllegalArgumentException("Degenerate helix");
   derivatives(controls,t,derivative,second);double speed=Math.sqrt(derivative[0]*derivative[0]+derivative[1]*derivative[1]+derivative[2]*derivative[2]);
   if(speed<1e-5||Math.abs(derivative[1])/speed>.35)throw new IllegalArgumentException("Helix slope/cusp");
   double cx=derivative[1]*second[2]-derivative[2]*second[1],cy=derivative[2]*second[0]-derivative[0]*second[2],cz=derivative[0]*second[1]-derivative[1]*second[0];
   curvature=Math.max(curvature,(float)(Math.sqrt(cx*cx+cy*cy+cz*cz)/(speed*speed*speed)));
   total+=distance;if(t<=3f/(controls.length/3-1))targetArc=total;
   while(next<SAMPLES&&t>=next/(float)(SAMPLES-1)){float pt=next/(float)(SAMPLES-1);point(controls,pt,points,next*4);points[next*4+3]=total-distance*Math.max(0,(t-pt)*denseSteps);next++;}
   System.arraycopy(dense,0,previous,0,3);
  }
  float length=length(roster.members.length),radius=radius(roster.members.length);
  if(total<=length+24||total>112||curvature>1/(4*radius))throw new IllegalArgumentException("Helix envelope length="+total+" curvature="+curvature+" radius="+radius);
  float speed=.95f*maxSpeed/(1+(radius+.6f)*curvature);
  return new Flight(roster.armA,roster.armB,length,radius,maxSpeed,speed,charge,thrust,thrust+(total-length)/speed+RAMP,curvature,targetArc,controls.clone(),points);
 }
 private static void derivatives(float[] controls,float parameter,double[] first,double[] second){
  int count=controls.length/3;double t=Math.max(0,Math.min(1,parameter))*(count-1);int segment=Math.min(count-2,(int)t);double u=t-segment;
  for(int a=0;a<3;a++){double p0=controls[Math.max(0,segment-1)*3+a],p1=controls[segment*3+a],p2=controls[(segment+1)*3+a],p3=controls[Math.min(count-1,segment+2)*3+a];double m1=segment==0?p2-p1:(p2-p0)*.5,m2=segment==count-2?p2-p1:(p3-p1)*.5;
   first[a]=(6*u*u-6*u)*p1+(3*u*u-4*u+1)*m1+(-6*u*u+6*u)*p2+(3*u*u-2*u)*m2;
   second[a]=(12*u-6)*p1+(6*u-4)*m1+(-12*u+6)*p2+(6*u-2)*m2;
  }
 }
 public static int phase(float now,float assemble,float lock,boolean locked,Flight f){
  if(AllayStarfall.elapsed(now,assemble)<0)return 1;
  if(!locked||AllayStarfall.elapsed(now,lock)<0)return 2;
  if(AllayStarfall.elapsed(now,f.thrust)<0)return 3;
  return AllayStarfall.elapsed(now,f.finish)<0?4:5;
 }
 public static double distanceSquared(Flight flight,double x,double y,double z){
  double closest=Double.POSITIVE_INFINITY;float[] samples=flight.samples;
  for(int i=4;i<samples.length;i+=4){double ax=samples[i-4],ay=samples[i-3],az=samples[i-2],dx=samples[i]-ax,dy=samples[i+1]-ay,dz=samples[i+2]-az;
   double t=Math.max(0,Math.min(1,((x-ax)*dx+(y-ay)*dy+(z-az)*dz)/Math.max(1e-8,dx*dx+dy*dy+dz*dz)));double rx=x-ax-dx*t,ry=y-ay-dy*t,rz=z-az-dz*t;closest=Math.min(closest,rx*rx+ry*ry+rz*rz);
  }return closest;
 }
 @FunctionalInterface public interface Clearance {boolean clear(float x,float y,float z,float radius);}
 public static boolean clearTube(Flight flight,float from,float until,Clearance test){
  float start=Math.max(0,from),end=Math.min(until,flight.arcLength());if(start>end)return true;
  float[] point=new float[3];int steps=Math.max(1,(int)Math.ceil((end-start)/.5f));
  for(int step=0;step<=steps;step++){float arc=start+(end-start)*step/steps;point(flight.controls,parameter(flight,arc),point,0);if(!test.clear(point[0],point[1],point[2],flight.radius+.775f))return false;}return true;
 }
}
