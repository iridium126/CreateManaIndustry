package com.iridium126.createmanaindustry.content.allaystorm;
/** Independent storm-level clocks. Missed opportunities never accumulate. */
public final class MajorAttackSchedule {
 private final long[] next={Long.MIN_VALUE,Long.MIN_VALUE};
 private int rotation;private final int[] targets={0,0};
 public boolean due(int kind,long tick,double seconds){int i=kind==StormAttack.HELIX?0:1;long interval=Math.max(1,Math.round(seconds*20));if(next[i]==Long.MIN_VALUE){next[i]=tick+interval;return false;}if(tick<next[i])return false;next[i]=tick+interval;return true;}
 public boolean ready(long tick){return next[0]!=Long.MIN_VALUE&&tick>=next[0]||next[1]!=Long.MIN_VALUE&&tick>=next[1];}
 public int first(){return rotation++%3;}
 public int target(int kind,int size){int i=kind==StormAttack.HELIX?0:1;return Math.floorMod(targets[i]++,size);}
 public void reset(){next[0]=next[1]=Long.MIN_VALUE;rotation=0;targets[0]=targets[1]=0;}
}
