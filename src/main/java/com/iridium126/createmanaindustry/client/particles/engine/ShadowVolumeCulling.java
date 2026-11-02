package com.iridium126.createmanaindustry.client.particles.engine;

/** Conservative directional-light extrusion test. Different support points per plane may retain extra geometry. */
public final class ShadowVolumeCulling {
    private ShadowVolumeCulling() {}
    public static boolean cylinderVisible(float[] planes,float x,float y,float z,float radius,float halfHeight,float dx,float dy,float dz){
        double begin=0,end=1e20;
        for(int i=0;i<24;i+=4){
            double nx=planes[i],ny=planes[i+1],nz=planes[i+2];
            double support=nx*x+ny*y+nz*z+planes[i+3]+Math.hypot(nx,nz)*radius+Math.abs(ny)*halfHeight;
            double direction=nx*dx+ny*dy+nz*dz;
            if(!Double.isFinite(support)||!Double.isFinite(direction))return true;
            if(Math.abs(direction)<1e-7){if(support<0)return false;}
            else if(direction>0)begin=Math.max(begin,-support/direction);
            else end=Math.min(end,-support/direction);
            if(begin>end)return false;
        }
        return true;
    }
}
