package com.iridium126.createmanaindustry.content.allaystorm.rift;

/** Pure authoritative boundary rules, independent of rendering and entity tracking. */
public final class RiftRules {
    public static final int OPEN_COUNT=16384, ENTER=512, LEAVE=576, SEARCH_RADIUS=32;
    private RiftRules(){}
    public static boolean eligible(boolean overworld,boolean active,int count,boolean alive,double x,double feetY,double z,double centerX,double centerZ,int sourceTop){
        if(!overworld||!active||count<OPEN_COUNT||!alive||!Double.isFinite(x)||!Double.isFinite(z)||!Double.isFinite(feetY))return false;
        double dx=x-centerX,dz=z-centerZ;return dx*dx+dz*dz<4096&&feetY>sourceTop+64.0;
    }
    public static boolean subscribed(boolean previous,double x,double z,double cx,double cz){double dx=x-cx,dz=z-cz,r=previous?LEAVE:ENTER;return dx*dx+dz*dz<=r*r;}
    public static boolean nativeBand(double minY,double maxY){return Double.isFinite(minY)&&Double.isFinite(maxY)&&minY<=maxY&&minY>=-128&&maxY<=384;}
}
