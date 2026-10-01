package com.iridium126.createmanaindustry.client.particles.packages;

/** Reused frame-level culling description. Safe box is unioned with planes, inside distance box. */
public final class PackageDrawCulling {
    public static final int MAX_PLANES=13;
    public final float[] planes=new float[MAX_PLANES*4];
    public int count;
    public float distance=-1,safe=-1;
    public void clear(){count=0;distance=safe=-1;}
    public void reject(){clear();count=1;planes[0]=planes[1]=planes[2]=0;planes[3]=-1e30f;}
    public void set(float[][] source,int count) {
        if(count<0 || count>MAX_PLANES || source.length<count)throw new IllegalArgumentException("Package culling planes");
        this.count=count;
        for(int i=0;i<count;i++) {
            float[] p=source[i];if(p.length<4)throw new IllegalArgumentException("Package plane layout");
            double n=Math.sqrt((double)p[0]*p[0]+(double)p[1]*p[1]+(double)p[2]*p[2]);
            for(int j=0;j<4;j++) {
                float value=(float)(p[j]/(n==0?1:n));
                if(!Float.isFinite(value))throw new IllegalArgumentException("Nonfinite package plane");
                planes[i*4+j]=value;
            }
        }
    }
}
