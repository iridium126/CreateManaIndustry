package com.iridium126.createmanaindustry.content.allaystorm;

import java.io.*;
import net.minecraft.world.phys.Vec3;

/** Shared cubic curtains and arc tables. Servers load ring metadata only. */
public final class DomeTemplate {
 public static final int VERSION=3,COUNT=12288,RECORD_WORDS=8,WORDS=4+COUNT*RECORD_WORDS;
 private static final String RESOURCE="/data/createmanaindustry/dome-template-v3.bin";
 private static final float[] DATA=loadMetadata();
 private static volatile int[] keys;private static volatile boolean keyLoadFailed;
 private static final java.util.concurrent.atomic.AtomicBoolean PRELOADING=new java.util.concurrent.atomic.AtomicBoolean();
 private DomeTemplate(){}
 public static boolean available(){return DATA.length==WORDS;}
 public static float word(int i){if(!available())throw new IllegalStateException("Dome template unavailable");return DATA[i];}
 public static int keyCount(){return (int)word(0);}
 public static boolean keysReady(){return keys!=null;}
 public static boolean keysFailed(){return keyLoadFailed;}
 public static void preloadKeys(){if(available()&&PRELOADING.compareAndSet(false,true))java.util.concurrent.CompletableFuture.runAsync(DomeTemplate::keys).exceptionally(error->{com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.error("Dome trajectory preload failed",error);return null;});}
 private static int record(int rank){if(rank<0||rank>=COUNT)throw new IndexOutOfBoundsException(rank);return 4+rank*RECORD_WORDS;}
 public static float duration(int rank){return word(record(rank)+2);}
 public static int ringBase(int rank){return (int)word(record(rank)+3);}
 public static int ringCount(int rank){return (int)word(record(rank)+7);}
 public static int curtain(int rank){return (rank-ringBase(rank))*12/ringCount(rank);}
 public static boolean coreChannel(int rank){int n=ringCount(rank)/12;return (ringBase(rank)/12+(rank-ringBase(rank))%n)%16==0;}
 public static double radius(int rank){return word(record(rank)+4);}
 public static double height(int rank){return word(record(rank)+5);}
 public static double angle(int rank){return word(record(rank)+6);}
 public static int mappedRank(int rank,double gatherSeconds){int count=ringCount(rank),base=ringBase(rank);int shift=(int)Math.floor(6*gatherSeconds/radius(rank)*count/(2*Math.PI)+.5);return base+Math.floorMod(rank-base+shift,count);}

 public static Vec3 gatherPoint(int rank,double seconds){double theta=angle(rank)+6*seconds/radius(rank);return new Vec3(radius(rank)*Math.cos(theta),height(rank),radius(rank)*Math.sin(theta));}
 public static Vec3 gatherVelocity(int rank,double seconds){double theta=angle(rank)+6*seconds/radius(rank);return new Vec3(-6*Math.sin(theta),0,6*Math.cos(theta));}
 public static Vec3 lockedPoint(int rank,double seconds,double gatherSeconds){int mapped=mappedRank(rank,gatherSeconds);var p=point(mapped,seconds*6);if(seconds>=1)return p;double age=Math.clamp(seconds,0,1),h=1-3*age*age+2*age*age*age,g=age-2*age*age+age*age*age;var offset=gatherPoint(rank,gatherSeconds).subtract(point(mapped,0));var dv=gatherVelocity(rank,gatherSeconds).subtract(startVelocity(mapped));return p.add(offset.scale(h)).add(dv.scale(g));}
 public static int[] keys(){var value=keys;if(value!=null)return value;synchronized(DomeTemplate.class){if(keys!=null)return keys;
  try(var input=open()){input.skipNBytes(16L+COUNT*RECORD_WORDS*4L);var result=new int[Math.multiplyExact(keyCount(),2)];for(int i=0;i<result.length;i++){result[i]=input.readInt();}if(input.read()!=-1)throw new IOException("Trailing Dome curves");
   for(int rank=0;rank<COUNT;rank++){int base=(int)word(record(rank))*2,count=(int)word(record(rank)+1);float previous=-1,parameter=-1;for(int k=0;k<count;k++){float arc=Float.intBitsToFloat(result[base+k*4+2]),t=(result[base+k*4+1]>>>16)*.001f;if(!Float.isFinite(arc)||arc<=previous||t<=parameter)throw new IOException("Dome arc order");previous=arc;parameter=t;}if(Math.abs(previous/6-duration(rank))>.00001)throw new IOException("Dome duration");}return keys=result;
  }catch(IOException failure){keyLoadFailed=true;throw new IllegalStateException("Dome curve resource failed",failure);}
 }}
 private static DataInputStream open()throws IOException{var stream=DomeTemplate.class.getResourceAsStream(RESOURCE);if(stream==null)throw new IOException("Missing Dome template");return new DataInputStream(new BufferedInputStream(stream));}
 private static double node(int rank,int point,int component){int at=(int)word(record(rank))*2+point*4;int[] data=keys();return switch(component){case 0->(data[at]&65535)*(80./65535)-40;case 1->(data[at]>>>16)*(80./65535)-40;case 2->(data[at+1]&65535)*(80./65535)-40;case 3->Float.intBitsToFloat(data[at+2]);case 7->(data[at+1]>>>16)*.001;default->{int shift=(component-4)*10;int packed=(data[at+3]>>>shift)&1023;yield (packed>=512?packed-1024:packed)*(6./511);}};}

 private static Vec3 vector(int rank,int point,int component){return new Vec3(node(rank,point,component),node(rank,point,component+1),node(rank,point,component+2));}
 private static Vec3 hermite(Vec3 p,Vec3 q,Vec3 v,Vec3 w,double u){double u2=u*u,u3=u2*u;return p.scale(2*u3-3*u2+1).add(q.scale(-2*u3+3*u2)).add(v.scale(u3-2*u2+u)).add(w.scale(u3-u2));}
 private static Vec3 derivative(Vec3 p,Vec3 q,Vec3 v,Vec3 w,double u){double u2=u*u;return p.scale(6*u2-6*u).add(q.scale(-6*u2+6*u)).add(v.scale(3*u2-4*u+1)).add(w.scale(3*u2-2*u));}
 private static double speed(Vec3 p,Vec3 q,Vec3 v,Vec3 w,double u){double a=6*u*u-6*u,c=3*u*u-4*u+1,d=3*u*u-2*u,x=a*(p.x-q.x)+c*v.x+d*w.x,y=a*(p.y-q.y)+c*v.y+d*w.y,z=a*(p.z-q.z)+c*v.z+d*w.z;return Math.sqrt(x*x+y*y+z*z);}
 private static final double[] GAUSS_X={.9602898564975363,.7966664774136267,.525532409916329,.1834346424956498},GAUSS_W={.1012285362903763,.2223810344533745,.3137066458778873,.362683783378362};
 private static double arc(Vec3 p,Vec3 q,Vec3 v,Vec3 w,double u){double sum=0;for(int i=0;i<4;i++)sum+=GAUSS_W[i]*(speed(p,q,v,w,u*(1-GAUSS_X[i])*.5)+speed(p,q,v,w,u*(1+GAUSS_X[i])*.5));return sum*u*.5;}
 private static Vec3 startVelocity(int rank){var p=vector(rank,0,0);var q=vector(rank,1,0);double dt=node(rank,1,7)-node(rank,0,7);var v=vector(rank,0,4).scale(dt);var w=vector(rank,1,4).scale(dt);return v.normalize().scale(6*arc(p,q,v,w,1)/(node(rank,1,3)-node(rank,0,3)));}
 public static Vec3 point(int rank,double distance){int count=(int)word(record(rank)+1);distance=Math.clamp(distance,0,duration(rank)*6);int lo=0,hi=count-1;while(hi-lo>1){int mid=(lo+hi)/2;if(node(rank,mid,3)<distance)lo=mid;else hi=mid;}
  Vec3 p=vector(rank,lo,0),q=vector(rank,hi,0);double dt=node(rank,hi,7)-node(rank,lo,7);Vec3 v=vector(rank,lo,4).scale(dt),w=vector(rank,hi,4).scale(dt);double local=distance-node(rank,lo,3),length=node(rank,hi,3)-node(rank,lo,3),u=Math.clamp(local/length,0,1);
  double total=arc(p,q,v,w,1),lower=0,upper=1;for(int k=0;k<8;k++){double travelled=arc(p,q,v,w,u)*length/Math.max(total,1e-9),error=local-travelled;if(Math.abs(error)<.000000001)break;if(error>0)lower=u;else upper=u;double next=u+error/Math.max(speed(p,q,v,w,u)*length/Math.max(total,1e-9),1e-9);u=next>lower&&next<upper?next:(lower+upper)*.5;}return hermite(p,q,v,w,u);
 }
 private static float[] loadMetadata(){try(var in=open()){
  int version=in.readInt(),count=in.readInt(),keyCount=in.readInt(),stride=in.readInt();if(version!=VERSION||count!=COUNT||keyCount<COUNT*4||keyCount>16777216||stride!=2)throw new IOException("Dome header");
  float[] result=new float[WORDS];result[0]=keyCount;result[1]=stride;result[2]=count;result[3]=version;
  for(int rank=0;rank<COUNT;rank++){int b=4+rank*RECORD_WORDS;for(int k=0;k<RECORD_WORDS;k++){result[b+k]=in.readFloat();if(!Float.isFinite(result[b+k]))throw new IOException("Dome metadata NaN");}
   int base=(int)result[b+3],n=(int)result[b+7];if(result[b]<0||result[b]+result[b+1]*2>keyCount||result[b+1]<2||result[b+1]>4096||result[b+2]<=.5||result[b+2]>60||base<0||base>rank||n<12||n%12!=0||rank>=base+n||base+n>COUNT||result[b+4]<1||result[b+5]<0)throw new IOException("Dome record");
  }return result;
 }catch(IOException failure){com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.error("Dome disabled: invalid curtain template",failure);return new float[0];}}
}
