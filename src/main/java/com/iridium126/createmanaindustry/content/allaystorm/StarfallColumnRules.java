package com.iridium126.createmanaindustry.content.allaystorm;

import java.util.List;
import java.util.function.BiPredicate;
import net.minecraft.world.phys.Vec3;

/** Pure rules shared by the server planner, packet validation and regression fixtures. */
public final class StarfallColumnRules {
    public static final int MEMBERS = 64, CAPACITY = 64, LEGACY_SLOTS = 4, GPU_SLOTS = 68;
    public static final float SPACING = 8, RADIUS = 4, BLOOM = .7f, STAGGER = .06f, ARRIVAL_GRACE = 2;
    public static final int COLUMN_KIND = 3, COLUMN_BIT = 0x40000000;
    private StarfallColumnRules() {}

    public static double smooth(double t) { t = Math.clamp(t, 0, 1); return t*t*t*(10+t*(-15+6*t)); }
    public static double smoothDerivative(double t) { return t<=0||t>=1?0:30*t*t*(1-t)*(1-t); }
    public static double height(double radius) { return .0625*radius*radius; }
    public static double arc(double r){double q=r/8;return .5*(r*Math.sqrt(1+q*q)+8*Math.log(q+Math.sqrt(1+q*q)));}
    public static double radiusAtArc(double arc){double r=Math.clamp(arc,0,4);for(int i=0;i<3;i++)r=Math.clamp(r-(arc(r)-arc)/Math.sqrt(1+r*r/64),0,4);return r;}
    public static double bloomSeconds(int rank){return (arc(4)-arc(root(rank)))/6;}
    public static float delay(int rank) { return rank*STAGGER; }
    public static double root(int rank) { return Math.sqrt(.04+(1.96-.04)*(rank+.5)/64); }
    public static Vec3 bloom(Vec3 landing,int rank,int seed,double age) {
        double r=radiusAtArc(arc(root(rank))+Math.max(0,age)*6),angle=((rank*27)&63)*Math.PI/32+seed*.0001f;
        return landing.add(Math.cos(angle)*r,height(r),Math.sin(angle)*r);
    }
    public static double gatherOffset(int rank){return (rank/4-7.5)*.7;}
    public static double orbitAngle(int rank,int seed,double elapsed){return (rank&3)*Math.PI/2+(rank/4)*.35+seed*.0001f+StormMotion.COLUMN_ORBIT/(.9+.3*(rank/4)/15.)*elapsed;}
    public static Vec3 gather(Vec3 origin,int rank,int seed) {return gather(origin,rank,seed,0);}
    public static Vec3 gather(Vec3 origin,int rank,int seed,double elapsed) {
        double angle=orbitAngle(rank,seed,elapsed),r=.9+.3*(rank/4)/15.;
        return origin.add(Math.cos(angle)*r,gatherOffset(rank),Math.sin(angle)*r);
    }
    public static Vec3 descent(Vec3 origin,Vec3 landing,int rank,int seed,double fraction) {return descent(origin,landing,rank,seed,fraction,0,20);}
    public static Vec3 descent(Vec3 origin,Vec3 landing,int rank,int seed,double fraction,double assembleAge,double duration) {
        double u=Math.clamp(fraction,0,1),s=smooth(u),first=orbitAngle(rank,seed,assembleAge),last=first+.7*duration;
        double angleEnd=((rank*27)&63)*Math.PI/32+seed*.0001f;
        last+=Math.atan2(Math.sin(angleEnd-last),Math.cos(angleEnd-last));
        double angle=(first+StormMotion.COLUMN_ORBIT/(.9+.3*(rank/4)/15.)*u*duration)*(1-s)+last*s,r=(.9+.3*(rank/4)/15.)*(1-s)+root(rank)*s;
        Vec3 top=gather(origin,rank,seed,assembleAge),foot=bloom(landing,rank,seed,0);
        return new Vec3(landing.x+Math.cos(angle)*r,top.y*(1-s)+foot.y*s,landing.z+Math.sin(angle)*r);
    }
    public static float earliestTouch(float assemble,float gatherY,float groundY,int rank){return assemble+(float)Math.max(0,(gatherY+gatherOffset(rank)-groundY-.2)/AllayStarfall.SPEED);}
    public static float renderSeconds(long gameTime,float partialTick){return (float)(((gameTime&((1<<21)-1))+Math.clamp(partialTick,0,1))/20.0);}
    public static Vec3 bridge(Vec3 origin,Vec3 velocity,Vec3 destination,double age,double duration) {
        double u=Math.clamp(age/duration,0,1);
        return origin.lerp(destination,smooth(u)).add(velocity.scale(duration*u*Math.pow(1-u,3)));
    }
    public static float descentSeconds(double height) { return (float)Math.max(2, 2.1*Math.max(0,height)/AllayStarfall.SPEED); }
    public static int assignment(int slot, int rank) {
        if(slot<4||slot>=GPU_SLOTS||rank<0||rank>=MEMBERS)throw new IllegalArgumentException("Column assignment");
        return COLUMN_BIT|1|((slot-4)<<1)|(rank<<8);
    }
    public static int slot(int assignment) { return (assignment&COLUMN_BIT)!=0?4+((assignment>>>1)&63):AllayStarfall.slot(assignment); }

    /** Search order is stable: outward, then alternating 15-degree offsets on each integer ring. */
    public static Vec3 landing(double x,double z,double centerX,double centerZ,double radius,int seed,
                               List<Vec3> occupied,BiPredicate<Double,Double> terrain) {
        centerX=(float)centerX;centerZ=(float)centerZ;
        double heading=x==centerX&&z==centerZ?(AllayStarfall.mix(seed)&0xffff)*(Math.PI*2/65536):Math.atan2(z-centerZ,x-centerX);
        if(available((float)x,(float)z,centerX,centerZ,radius,occupied,terrain))return new Vec3((float)x,0,(float)z);
        for(int ring=1;ring<=Math.ceil(radius*2);ring++)for(int step=0;step<24;step++) {
            int offset=step==0?0:(step+1)/2*(step%2==1?1:-1);
            double angle=heading+offset*Math.PI/12,px=(float)(x+ring*Math.cos(angle)),pz=(float)(z+ring*Math.sin(angle));
            if(available(px,pz,centerX,centerZ,radius,occupied,terrain))return new Vec3(px,0,pz);
        }
        return null;
    }
    private static boolean available(double x,double z,double cx,double cz,double radius,List<Vec3> occupied,BiPredicate<Double,Double> terrain) {
        if((x-cx)*(x-cx)+(z-cz)*(z-cz)>radius*radius+1e-8)return false;
        for(Vec3 p:occupied)if((x-p.x)*(x-p.x)+(z-p.z)*(z-p.z)<SPACING*SPACING-1e-5)return false;
        return terrain.test(x,z);
    }
}
