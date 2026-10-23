package com.iridium126.createmanaindustry.content.allaystorm;

import java.util.BitSet;

public final class DomeGatherRules {
    private DomeGatherRules() {}
    public static int durationTicks(float start,float deadline){return Math.max(1,Math.round(deadline*20)-Math.round(start*20));}
    public static int elapsedTicks(float now,float start){int ticks=(int)Math.floor(now*20.0f+.0001f)-Math.round(start*20);return ticks< -1048576?ticks+2097152:ticks>1048576?ticks-2097152:ticks;}
    public static boolean withinDeadline(float now,float start,float deadline){int elapsed=elapsedTicks(now,start);return elapsed>=0&&elapsed<=durationTicks(start,deadline);}
    public static int required(int eligible,int elapsed,int duration){
        if(eligible<0||eligible>StormGatherRules.MAX_MEMBERS||duration<1)throw new IllegalArgumentException("Dome gather bounds");
        long scale=10L*duration,progress=Math.clamp(elapsed,0,duration);
        return (int)(((scale-progress)*eligible+scale-1)/scale);
    }
    public static boolean ready(int ready,int eligible,int elapsed,int duration){return eligible>0&&ready>=0&&ready<=eligible&&ready>=required(eligible,elapsed,duration);}
    public static boolean valid(BitSet ready,BitSet eligible,int count,float now,float start,float deadline){
        if(!withinDeadline(now,start,deadline)||ready.length()>count||eligible.length()>count)return false;
        var excess=(BitSet)ready.clone();excess.andNot(eligible);
        return excess.isEmpty()&&ready(ready.cardinality(),eligible.cardinality(),elapsedTicks(now,start),durationTicks(start,deadline));
    }
}
