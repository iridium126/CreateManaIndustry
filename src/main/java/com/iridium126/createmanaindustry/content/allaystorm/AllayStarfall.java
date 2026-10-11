package com.iridium126.createmanaindustry.content.allaystorm;
import java.util.Arrays;
import java.util.BitSet;
/** Bounded starfall rules. Members are identities, never pool slots. */
public final class AllayStarfall {
 public static final int MAX_COLUMNS=32, MAX_MEMBERS=131072, RING_PARTICLES=128;
 public static final float SPEED=6, COLUMN_GAP=.35f, WARNING=1, PETALS=1.5f, FADE=.25f;
 public static final int START=0, UPDATE=1, LOCK=2, ABORT=3;
 public record Selection(int columns,int perColumn,int[] members) {}
 private AllayStarfall() {}
 public static int columns(int alive,int step,int maximum){return alive<=0?0:Math.min(Math.max(1,Math.min(MAX_COLUMNS,maximum)),Math.max(1,alive/Math.max(1,step)));}
 public static Selection select(int generated,BitSet dead,BitSet reserved,int requested,int perColumn,int seed){
  generated=Math.max(0,Math.min(MAX_MEMBERS,generated));perColumn=Math.max(1,Math.min(4096,perColumn));
  int quota=Math.min(generated,Math.max(0,Math.min(MAX_COLUMNS,requested))*perColumn);
  int[] chosen=selectExact(generated,dead,reserved,quota,seed);
  return new Selection((chosen.length+perColumn-1)/perColumn,perColumn,chosen);
 }
 public static int[] selectExact(int generated,BitSet dead,BitSet reserved,int quota,int seed){
  generated=Math.max(0,Math.min(MAX_MEMBERS,generated));quota=Math.max(0,Math.min(generated,quota));
  int[] chosen=new int[quota];int size=0,offset=mix(seed)&(MAX_MEMBERS-1),stride=(mix(seed^0x619c731b)|1)&(MAX_MEMBERS-1);
  for(int rank=0;rank<MAX_MEMBERS&&size<quota;rank++){int member=(offset+rank*stride)&(MAX_MEMBERS-1);if(member<generated&&!dead.get(member)&&!reserved.get(member))chosen[size++]=member;}
  return Arrays.copyOf(chosen,size);
 }
 public static long epoch(long created,int seed){return created*0x9e3779b97f4a7c15L^Integer.toUnsignedLong(seed);}
 public static int mix(int n){n^=n>>>16;n*=0x7feb352d;n^=n>>>15;n*=0x846ca68b;return n^(n>>>16);}
 public static float crownRadius(float r,int columns){return Math.max(r*.7f,columns*4f/(float)(2*Math.PI));}
 public static float gatherSeconds(float radius){return Math.max(4,2+2.3f*radius/SPEED);}
 public static float offsetX(int column,int seed){return column==0?0:(float)(1.8*Math.sqrt(column)*Math.cos(angle(column,seed)));}
 public static float offsetZ(int column,int seed){return column==0?0:(float)(1.8*Math.sqrt(column)*Math.sin(angle(column,seed)));}
 private static double angle(int column,int seed){return column*2.399963229728653+((mix(seed)&0xffff)/65536.0)*2*Math.PI;}
 public static float pathSeconds(float[] path){double length=0;for(int i=3;i<path.length;i+=3){double x=path[i]-path[i-3],y=path[i+1]-path[i-2],z=path[i+2]-path[i-1];length+=Math.sqrt(x*x+y*y+z*z);}return (float)(length/SPEED*1.25+2);}
 public static int pack(int slot,int column,int rank){return 1|(slot<<1)|(column<<3)|(rank<<8);}
 public static int slot(int packed){return(packed>>>1)&3;}
 public static int column(int packed){return(packed>>>3)&31;}
 public static int rank(int packed){return packed>>>8;}
 public static float elapsed(float now,float then){float d=now-then;return d< -52428.8f?d+104857.6f:d>52428.8f?d-104857.6f:d;}
 /** Two seconds of server-owned positions; no per-tick object allocation. */
 public static final class PlayerTrail {
  private final long[] ticks=new long[41];private final double[] xyz=new double[123];private int cursor,count;
  public void record(long tick,double x,double y,double z){ticks[cursor]=tick;int b=cursor*3;xyz[b]=x;xyz[b+1]=y;xyz[b+2]=z;cursor=(cursor+1)%41;count=Math.min(41,count+1);}
  public double distanceSquared(long sample,double x,double y,double z){int best=-1;long difference=Long.MAX_VALUE;for(int i=0;i<count;i++){long d=Math.abs(ticks[i]-sample);if(d<difference){difference=d;best=i;}}if(best<0||difference>2)return Double.POSITIVE_INFINITY;int b=best*3;double dx=x-xyz[b],dy=y-xyz[b+1],dz=z-xyz[b+2];return dx*dx+dy*dy+dz*dz;}
 }
}
