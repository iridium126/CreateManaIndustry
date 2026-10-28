package com.iridium126.createmanaindustry.client.allaystorm.rift;

/** One immutable canonical sample; independent of camera, Iris and member simulation. */
public record StormCloudFrame(double x,double y,double z,double radius,double coneSlope,
                              double planeY,float strength,String reason) {
    public static final StormCloudFrame EMPTY=new StormCloudFrame(0,0,0,0,0,0,0,"no source storm");
    public boolean active(){return coneSlope>0&&strength>0;}
    public static double smooth(double t){t=Math.clamp(t,0,1);return t*t*t*(t*(t*6-15)+10);}
    public static float strength(double count,double ageTicks,double stopTicks){
        return (float)((.15+.85*smooth(count/16384))*smooth(ageTicks/40)*smooth(stopTicks/40));
    }
    /** CPU oracle for the shader contract, also used by headless boundary tests. */
    public double density(double dx,double worldY,double dz){
        if(!active()||worldY>=planeY)return 1;
        double r0=Math.max(16,coneSlope*Math.abs(worldY-64));
        double r1=r0+Math.max(16,.15*r0),r2=r0+Math.max(48,.5*r0),r3=r2+Math.max(32,.25*r0);
        double d2=dx*dx+dz*dz;
        double ring=2*smooth((d2-r0*r0)/(r1*r1-r0*r0))-smooth((d2-r2*r2)/(r3*r3-r2*r2));
        return 1+(ring-1)*strength*smooth((planeY-worldY)/16);
    }
}
