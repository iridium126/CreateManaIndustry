package com.iridium126.createmanaindustry.content.allaystorm;
import java.io.*;
/** Offline, equally spaced arc tables. No path generation or neighbour search during play. */
public final class DomeTemplate {
 public static final int VERSION=1,COUNT=8192,MAX_LANES=64,KNOTS=1024,WORDS=4+COUNT*4+MAX_LANES*KNOTS*4;
 private static final float[] DATA=load();
 private DomeTemplate(){}
 public static boolean available(){return DATA.length==WORDS;}
 public static float word(int i){if(!available())throw new IllegalStateException("Dome template unavailable");return DATA[i];}
 public static float duration(int rank){return (word(4+rank*4+2)-word(4+rank*4+1))/6;}
 public static net.minecraft.world.phys.Vec3 point(int rank,double distance){
  int r=4+rank*4,lane=(int)word(r),base=4+COUNT*4+lane*KNOTS*4;double total=word(r+2),arc=Math.clamp(word(r+1)+distance,0,total);int lo=0,hi=KNOTS-1;
  while(hi-lo>1){int mid=(lo+hi)/2;if(word(base+mid*4+3)<arc)lo=mid;else hi=mid;}
  int b=base+lo*4;double f=(arc-word(b+3))/(word(b+7)-word(b+3));
  return new net.minecraft.world.phys.Vec3(word(b)*(1-f)+word(b+4)*f,word(b+1)*(1-f)+word(b+5)*f,word(b+2)*(1-f)+word(b+6)*f);
 }
 private static float[] load(){
  try(var stream=DomeTemplate.class.getResourceAsStream("/data/createmanaindustry/dome-template-v1.bin")){
   if(stream==null)throw new IOException("Missing dome template");var in=new DataInputStream(new BufferedInputStream(stream));int version=in.readInt(),count=in.readInt(),lanes=in.readInt(),knots=in.readInt();
   if(version!=VERSION||count!=COUNT||lanes<1||lanes>MAX_LANES||knots!=KNOTS)throw new IOException("Dome template header");
   float[] data=new float[WORDS];data[0]=lanes;data[1]=knots;data[2]=count;data[3]=version;
   for(int i=4;i<4+COUNT*4+lanes*KNOTS*4;i++){data[i]=in.readFloat();if(!Float.isFinite(data[i]))throw new IOException("Nonfinite dome template");}
   for(int rank=0;rank<COUNT;rank++){int b=4+rank*4,lane=(int)data[b];if(lane<0||lane>=lanes||data[b]!=lane||data[b+1]<0||data[b+2]<=data[b+1]||data[b+2]-data[b+1]>360)throw new IOException("Dome template arc");}
   for(int lane=0;lane<lanes;lane++){int base=4+COUNT*4+lane*KNOTS*4;float previous=-1;for(int k=0;k<KNOTS;k++){float arc=data[base+k*4+3];if(arc<0||arc<=previous)throw new IOException("Dome arc order");previous=arc;}int end=base+(KNOTS-1)*4;double x=data[end],y=data[end+1],z=data[end+2];if(x*x+y*y+z*z>=36||y<0)throw new IOException("Dome exit outside core");}
   if(in.read()!=-1)throw new IOException("Trailing dome template");return data;
  }catch(IOException failure){com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.error("Dome disabled: invalid 8192-member template",failure);return new float[0];}
 }
}
