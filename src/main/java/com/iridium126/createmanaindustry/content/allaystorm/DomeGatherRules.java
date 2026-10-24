package com.iridium126.createmanaindustry.content.allaystorm;
import java.util.BitSet;
/** Source-compatible facade; Dome and Helix use the same dynamic rule. */
public final class DomeGatherRules {
 private DomeGatherRules(){}
 public static int durationTicks(float start,float deadline){return StormGatherRules.durationTicks(start,deadline);}
 public static int elapsedTicks(float now,float start){return StormGatherRules.elapsedTicks(now,start);}
 public static boolean withinDeadline(float now,float start,float deadline){return StormGatherRules.withinDeadline(now,start,deadline);}
 public static int required(int eligible,int elapsed,int duration){return StormGatherRules.required(eligible,elapsed,duration);}
 public static boolean ready(int ready,int eligible,int elapsed,int duration){return StormGatherRules.ready(ready,eligible,elapsed,duration);}
 public static boolean valid(BitSet ready,BitSet eligible,int count,float now,float start,float deadline){return StormGatherRules.valid(ready,eligible,count,now,start,deadline);}
}
