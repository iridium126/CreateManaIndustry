package com.iridium126.createmanaindustry.content.allaystorm;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

/** Server-owned, bounded rules shared with the attack packet and GPU table. */
public final class AllayDome {
    public static final int STARFALL=0, DOME=1, MIN_MEMBERS=768, MAX_MEMBERS=24576, GROUPS=7, CROWN=6;
    public static final float WARNING=1.5f, FOLLOW_SPEED=2, MAX_ASCENT=28;
    public static final int NO_FALLBACK=0, TOO_FEW=1, TERRAIN=2;
    private AllayDome() {}
    public static boolean choose(double random,double chance){return random<Math.max(0,Math.min(1,chance));}
    public static boolean targetRequired(int kind,boolean locked){return kind!=DOME||!locked;}
    /** Distance to the locked attack cylinder, independent of the moving storm anchor. */
    public static double distanceSquared(double x,double y,double z,double cx,double cy,double cz,float radius){
        double dx=x-cx,dz=z-cz,horizontal=Math.max(0,Math.sqrt(dx*dx+dz*dz)-radius);
        double vertical=y<cy?cy-y:y>cy+MAX_ASCENT?y-cy-MAX_ASCENT:0;
        return horizontal*horizontal+vertical*vertical;
    }
    public static int quota(int alive,double fraction,int maximum){return Math.min(Math.max(0,Math.min(MAX_MEMBERS,maximum)),(int)Math.floor(Math.max(0,alive)*Math.max(0,Math.min(1,fraction))));}
    public static int[] select(int generated,BitSet dead,BitSet reserved,int quota,int seed){
        return AllayStarfall.selectExact(generated,dead,reserved,quota,seed);
    }
    /** Six petal groups followed by the crown; boundaries are exclusive. */
    public static int[] groups(int count){
        int crown=count/10,rest=count-crown,base=rest/6,extra=rest%6,total=0;
        int[] ends=new int[7];for(int g=0;g<6;g++)ends[g]=total+=base+(g<extra?1:0);ends[6]=count;return ends;
    }
    public static int group(int[] ends,int rank){for(int g=0;g<ends.length;g++)if(rank<ends[g])return g;return -1;}
    public static int[] columns(int count,int per){int[] ends=new int[(count+per-1)/per];for(int c=0;c<ends.length;c++)ends[c]=Math.min(count,(c+1)*per);return ends;}
    public static float radius(int count){return Math.max(8,Math.min(20,(float)Math.sqrt(count)/6));}
    public static float closeSeconds(float radius){return 2+1.25f*radius/6;}
    public static float warningStart(float lock,float radius,int petal){return lock+petal*(WARNING+closeSeconds(radius));}
    public static int memberPhase(float now,float lock,float radius,int group){
        float t=AllayStarfall.elapsed(now,lock);
        if(t<0)return 2;
        if(group==CROWN)return t<5*(WARNING+closeSeconds(radius))-1?3:5;
        float age=t-group*(WARNING+closeSeconds(radius));
        if(age<0)return 3;if(age<WARNING)return 3;if(age<WARNING+closeSeconds(radius))return 4;return 5;
    }
    public static int closingPetal(float now,float lock,float radius){
        float t=AllayStarfall.elapsed(now,lock);if(t<0)return -1;
        int g=(int)(t/(WARNING+closeSeconds(radius)));
        return g<6&&t-g*(WARNING+closeSeconds(radius))>=WARNING?g:-1;
    }
    public static int snapshotPhase(float now,float assemble,float lock,boolean locked,float radius){
        if(!locked)return AllayStarfall.elapsed(now,assemble)<0?1:2;
        float age=AllayStarfall.elapsed(now,lock);
        if(age>=6*(WARNING+closeSeconds(radius)))return 5;
        return closingPetal(now,lock,radius)>=0?4:3;
    }
    /** Player-sized escape lanes lie halfway between ribbons, never through a petal. */
    public interface Terrain { double floor(double x,double z); boolean clear(double x,double y,double z); }
    public static boolean hasExit(Terrain terrain,double x,double y,double z,float radius,float orientation){
        for(int lane=0;lane<6;lane++){
            double angle=orientation+(lane+.5)*Math.PI/3,dx=Math.cos(angle),dz=Math.sin(angle),previous=y;boolean safe=true;
            for(double distance=0;distance<=radius+2;distance+=.5){
                double px=x+dx*distance,pz=z+dz*distance,ground=terrain.floor(px,pz);
                if(!Double.isFinite(ground)||Math.abs(ground-previous)>1||Math.abs(ground-y)>3||!terrain.clear(px,ground,pz)){safe=false;break;}
                previous=ground;
            }
            if(safe)return true;
        }return false;
    }
    /** Independent victims can receive the same member's contact once. */
    public static final class Contacts {
        private final Map<UUID,IntOpenHashSet> victims=new HashMap<>();
        public boolean add(UUID player,int member){return victims.computeIfAbsent(player,k->new IntOpenHashSet()).add(member);}
    }
}
