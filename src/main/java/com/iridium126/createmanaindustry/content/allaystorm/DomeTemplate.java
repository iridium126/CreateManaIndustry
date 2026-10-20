package com.iridium126.createmanaindustry.content.allaystorm;
import java.io.*;
import net.minecraft.world.phys.Vec3;
/** Shared offline trajectories. Servers load only the small roster metadata; clients load keys lazily. */
public final class DomeTemplate {
 public static final int VERSION=2,COUNT=12288,WORDS=4+COUNT*4;
 private static final String RESOURCE="/data/createmanaindustry/dome-template-v2.bin";
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
 public static float duration(int rank){return word(4+rank*4+2);}
 public static int[] keys(){
  var value=keys;if(value!=null)return value;
  synchronized(DomeTemplate.class){if(keys!=null)return keys;
   try(var input=open()){input.skipNBytes(16L+COUNT*16L);var result=new int[Math.multiplyExact(keyCount(),2)];for(int i=0;i<result.length;i++)result[i]=input.readInt();if(input.read()!=-1)throw new IOException("Trailing Dome keys");
    for(int rank=0;rank<COUNT;rank++){int base=(int)word(4+rank*4),count=(int)word(5+rank*4),previous=-1;
     for(int k=0;k<count;k++){int time=result[(base+k)*2+1]>>>16;if(time<=previous||time>60000||k==0&&time!=0)throw new IOException("Dome key time order");previous=time;}
     if(previous!=Math.round(duration(rank)*1000))throw new IOException("Dome duration mismatch");
    }return keys=result;}
   catch(IOException failure){keyLoadFailed=true;throw new IllegalStateException("Dome trajectory resource failed",failure);}
  }
 }
 private static DataInputStream open()throws IOException{var stream=DomeTemplate.class.getResourceAsStream(RESOURCE);if(stream==null)throw new IOException("Missing Dome template");return new DataInputStream(new BufferedInputStream(stream));}
 public static double keyTime(int index){return (keys()[index*2+1]>>>16)*.001;}
 private static Vec3 keyPoint(int index){int a=keys()[index*2],b=keys()[index*2+1];double scale=80./65535;return new Vec3((a&65535)*scale-40,(a>>>16)*scale-40,(b&65535)*scale-40);}
 public static Vec3 point(int rank,double distance){
  int record=4+rank*4,base=(int)word(record),count=(int)word(record+1);double time=Math.clamp(distance/6,0,duration(rank));int lo=0,hi=count-1;
  while(hi-lo>1){int mid=(lo+hi)/2;if(keyTime(base+mid)<time)lo=mid;else hi=mid;}
  double start=keyTime(base+lo),end=keyTime(base+hi);return keyPoint(base+lo).lerp(keyPoint(base+hi),Math.clamp((time-start)/(end-start),0,1));
 }
 private static float[] loadMetadata(){
  try(var in=open()){
   int version=in.readInt(),count=in.readInt(),keyCount=in.readInt(),stride=in.readInt();
   if(version!=VERSION||count!=COUNT||keyCount<COUNT*2||keyCount>16777216||stride!=2)throw new IOException("Dome header");
   float[] result=new float[WORDS];result[0]=keyCount;result[1]=stride;result[2]=count;result[3]=version;int next=0;
   for(int rank=0;rank<COUNT;rank++){int b=4+rank*4;for(int k=0;k<4;k++){result[b+k]=in.readFloat();if(!Float.isFinite(result[b+k]))throw new IOException("Dome metadata NaN");}
    int base=(int)result[b],length=(int)result[b+1];if(result[b]!=base||base!=next||result[b+1]!=length||length<2||length>2048||base+length>keyCount||result[b+2]<=0||result[b+2]>60)throw new IOException("Dome record");next=base+length;
   }if(next!=keyCount)throw new IOException("Unclaimed Dome keys");return result;
  }catch(IOException failure){com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.error("Dome disabled: invalid 12288-member template",failure);return new float[0];}
 }
}
