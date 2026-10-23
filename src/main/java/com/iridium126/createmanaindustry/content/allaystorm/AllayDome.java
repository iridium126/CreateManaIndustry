package com.iridium126.createmanaindustry.content.allaystorm;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

/** Server-owned, bounded rules shared with the attack packet and GPU table. */
public final class AllayDome {
    public static final int STARFALL=0, DOME=1, MIN_MEMBERS=16384, MAX_MEMBERS=12288, GROUPS=1;
    public static final float MAX_ASCENT=32;
    private AllayDome() {}
    public static boolean targetRequired(int kind,boolean locked){return StormAttack.targetRequired(kind,locked);}
    /** Distance to the locked attack cylinder, independent of the moving storm anchor. */
    public static double distanceSquared(double x,double y,double z,double cx,double cy,double cz,float radius){
        double dx=x-cx,dz=z-cz,horizontal=Math.max(0,Math.sqrt(dx*dx+dz*dz)-radius);
        double vertical=y<cy?cy-y:y>cy+MAX_ASCENT?y-cy-MAX_ASCENT:0;
        return horizontal*horizontal+vertical*vertical;
    }
    public static int quota(int alive){return alive>=MIN_MEMBERS?MAX_MEMBERS:0;}
    public static int[] select(int generated,BitSet dead,BitSet reserved,int quota,int seed){
        return AllayStarfall.selectExact(generated,dead,reserved,quota,seed);
    }
    /** Single fixed roster; boundaries are exclusive. */
    public static int[] groups(int count){return new int[]{count};}
    public static int group(int[] ends,int rank){for(int g=0;g<ends.length;g++)if(rank<ends[g])return g;return -1;}
    public static int[] columns(int count,int per){int[] ends=new int[(count+per-1)/per];for(int c=0;c<ends.length;c++)ends[c]=Math.min(count,(c+1)*per);return ends;}
    public static float radius(int count){return 32;}
    public static float closeSeconds(float radius){return 60;}
    public static boolean overlapsStormBody(double horizontalDistance,double verticalOffset,double stormRadius){
        if(!Double.isFinite(horizontalDistance)||!Double.isFinite(verticalOffset)||!Double.isFinite(stormRadius)||horizontalDistance<0||stormRadius<2||stormRadius>64)throw new IllegalArgumentException("Dome overlap bounds");
        double band=Math.clamp(.4*stormRadius,3,8),bottom=-1.9-band*.5,top=Math.clamp(.2*stormRadius,2,6)+band*.5+1.3;
        if(verticalOffset>top)return false;
        double horizontalGap=Math.max(0,horizontalDistance-(1.15*stormRadius+.75)),verticalGap=Math.max(0,bottom-verticalOffset);
        return horizontalGap*horizontalGap+verticalGap*verticalGap<=32*32;
    }
    public static float gatherSeconds(double targetDistance,double stormRadius){
        if(!Double.isFinite(targetDistance)||!Double.isFinite(stormRadius)||targetDistance<0||stormRadius<0)throw new IllegalArgumentException("Dome gather distance");
        double speed=StormMotion.INTERCEPT,acceleration=18,route=targetDistance+1.15*stormRadius+32;
        double seconds=route/speed+32/(speed-StormMotion.CRUISE)+2*speed/acceleration+Math.PI/Math.min(Math.PI,acceleration/speed)+.75+1;
        return (float)(Math.ceil(Math.max(8,seconds)*20)/20);
    }
    public static float warningStart(float lock,float radius,int group){return lock;}
    /** Independent victims can receive the same member's contact once. */
    public static final class Contacts {
        private final Map<UUID,IntOpenHashSet> victims=new HashMap<>();
        public boolean add(UUID player,int member){return victims.computeIfAbsent(player,k->new IntOpenHashSet()).add(member);}
    }
}
