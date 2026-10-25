package com.iridium126.createmanaindustry.content.allaystorm;
import java.util.function.DoubleSupplier;
/** Independent storm-level clocks. Missed opportunities never accumulate. */
public final class MajorAttackSchedule {
 private final long[] next={Long.MIN_VALUE,Long.MIN_VALUE};
 private int rotation;private final int[] targets={0,0};
 public boolean due(int kind,long tick,double minSeconds,double maxSeconds,DoubleSupplier random){
  int i=kind==StormAttack.HELIX?0:1;
  boolean initialized=next[i]!=Long.MIN_VALUE;
  if(initialized&&tick<next[i])return false;
  double lo=Math.min(minSeconds,maxSeconds),hi=Math.max(minSeconds,maxSeconds);
  double seconds=lo==hi?lo:lo+(hi-lo)*random.getAsDouble();
  next[i]=tick+Math.max(1,Math.round(seconds*20));
  return initialized;
 }
 public boolean ready(long tick){return next[0]!=Long.MIN_VALUE&&tick>=next[0]||next[1]!=Long.MIN_VALUE&&tick>=next[1];}
 public int first(){return rotation++%3;}
 public int target(int kind,int size){int i=kind==StormAttack.HELIX?0:1;return Math.floorMod(targets[i]++,size);}
 public void reset(){next[0]=next[1]=Long.MIN_VALUE;rotation=0;targets[0]=targets[1]=0;}
}
