package com.iridium126.createmanaindustry.content.allaystorm;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

/** Server-owned, bounded rules shared with the attack packet and GPU table. */
public final class AllayDome {
    public static final int STARFALL=0, DOME=1, MIN_MEMBERS=16384, MAX_MEMBERS=8192, GROUPS=1;
    public static final float MAX_ASCENT=32;
    private AllayDome() {}
    public static boolean targetRequired(int kind,boolean locked){return StormAttack.targetRequired(kind,locked);}
    /** Distance to the locked attack cylinder, independent of the moving storm anchor. */
    public static double distanceSquared(double x,double y,double z,double cx,double cy,double cz,float radius){
        double dx=x-cx,dz=z-cz,horizontal=Math.max(0,Math.sqrt(dx*dx+dz*dz)-radius);
        double vertical=y<cy?cy-y:y>cy+MAX_ASCENT?y-cy-MAX_ASCENT:0;
        return horizontal*horizontal+vertical*vertical;
    }
    public static int quota(int alive){return alive>=MIN_MEMBERS?8192:0;}
    public static int[] select(int generated,BitSet dead,BitSet reserved,int quota,int seed){
        return AllayStarfall.selectExact(generated,dead,reserved,quota,seed);
    }
    /** Single fixed roster; boundaries are exclusive. */
    public static int[] groups(int count){return new int[]{count};}
    public static int group(int[] ends,int rank){for(int g=0;g<ends.length;g++)if(rank<ends[g])return g;return -1;}
    public static int[] columns(int count,int per){int[] ends=new int[(count+per-1)/per];for(int c=0;c<ends.length;c++)ends[c]=Math.min(count,(c+1)*per);return ends;}
    public static float radius(int count){return 32;}
    public static float closeSeconds(float radius){return 60;}
    public static float warningStart(float lock,float radius,int group){return lock;}
    /** Independent victims can receive the same member's contact once. */
    public static final class Contacts {
        private final Map<UUID,IntOpenHashSet> victims=new HashMap<>();
        public boolean add(UUID player,int member){return victims.computeIfAbsent(player,k->new IntOpenHashSet()).add(member);}
    }
}
