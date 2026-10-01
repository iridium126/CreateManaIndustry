package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Reload-time attributes for Iris. The ordinary 48-byte vertex stream stays unchanged. */
public final class PackageMeshAttributes {
    public static final int BYTES=36; // normal.xyz, tangent.xyzw, quad midpoint UV
    private PackageMeshAttributes() {}

    public static ByteBuffer build(ByteBuffer vertices,ByteBuffer ranges,int meshes,ByteBuffer rawNormals) {
        if(vertices.remaining()%48!=0 || ranges.remaining()!=meshes*16 || meshes<0)
            throw new IllegalArgumentException("Package mesh attributes layout");
        int count=vertices.remaining()/48;
        if(rawNormals!=null && rawNormals.remaining()!=count*12)
            throw new IllegalArgumentException("Package raw normals layout");
        ByteBuffer v=vertices.slice().order(ByteOrder.nativeOrder()),r=ranges.slice().order(ByteOrder.nativeOrder());
        ByteBuffer normals=rawNormals==null?null:rawNormals.slice().order(ByteOrder.nativeOrder());
        ByteBuffer out=ByteBuffer.allocateDirect(Math.multiplyExact(count,BYTES)).order(ByteOrder.nativeOrder());
        for(int m=0;m<meshes;m++) {
            int first=r.getInt(m*16),end=Math.addExact(first,r.getInt(m*16+4));
            if(first<0 || end<first || end>count || (end-first)%3!=0)
                throw new IllegalArgumentException("Package attribute mesh range");
            for(int p=first;p<end;) {
                // Create emits 0,1,2,2,3,0. Recognise each quad locally, including
                // mesh ranges whose first vertex is not a multiple of six.
                boolean quad=p+6<=end && same(v,p+2,p+3) && same(v,p,p+5);
                int size=quad?6:3;
                float[] a=vertex(v,p),b=vertex(v,p+1),c=vertex(v,p+2);
                float ex=b[0]-a[0],ey=b[1]-a[1],ez=b[2]-a[2];
                float fx=c[0]-a[0],fy=c[1]-a[1],fz=c[2]-a[2];
                float[] face=unit(ey*fz-ez*fy,ez*fx-ex*fz,ex*fy-ey*fx,0,1,0);
                float du=b[3]-a[3],dv=b[4]-a[4],eu=c[3]-a[3],ev=c[4]-a[4];
                float det=du*ev-eu*dv;
                float tx=0,ty=0,tz=0,bx=0,by=0,bz=0;
                if(Math.abs(det)>1e-12f) {
                    tx=(ex*ev-fx*dv)/det;ty=(ey*ev-fy*dv)/det;tz=(ez*ev-fz*dv)/det;
                    bx=(fx*du-ex*eu)/det;by=(fy*du-ey*eu)/det;bz=(fz*du-ez*eu)/det;
                }
                float midU=0,midV=0;
                int[] unique=quad?new int[]{0,1,2,4}:new int[]{0,1,2};
                for(int q:unique){midU+=v.getFloat((p+q)*48+12);midV+=v.getFloat((p+q)*48+16);}
                midU/=unique.length;midV/=unique.length;
                for(int q=0;q<size;q++) {
                    int i=p+q,o=i*BYTES;
                    float nx=normals==null?v.getFloat(i*48+20):normals.getFloat(i*12);
                    float ny=normals==null?v.getFloat(i*48+24):normals.getFloat(i*12+4);
                    float nz=normals==null?v.getFloat(i*48+28):normals.getFloat(i*12+8);
                    float[] n=unit(nx,ny,nz,face[0],face[1],face[2]);
                    float dot=tx*n[0]+ty*n[1]+tz*n[2];
                    float[] fallback=Math.abs(n[1])<.9f?unit(n[2],0,-n[0],1,0,0):unit(0,-n[2],n[1],1,0,0);
                    float[] t=unit(tx-dot*n[0],ty-dot*n[1],tz-dot*n[2],fallback[0],fallback[1],fallback[2]);
                    float handed=(n[1]*t[2]-n[2]*t[1])*bx+(n[2]*t[0]-n[0]*t[2])*by+(n[0]*t[1]-n[1]*t[0])*bz;
                    out.putFloat(o,n[0]).putFloat(o+4,n[1]).putFloat(o+8,n[2]);
                    out.putFloat(o+12,t[0]).putFloat(o+16,t[1]).putFloat(o+20,t[2]).putFloat(o+24,handed<0?-1:1);
                    out.putFloat(o+28,midU).putFloat(o+32,midV);
                }
                p+=size;
            }
        }
        return out;
    }
    private static boolean same(ByteBuffer v,int a,int b) {
        for(int j=0;j<5;j++)if(v.getInt(a*48+j*4)!=v.getInt(b*48+j*4))return false;
        return true;
    }
    private static float[] vertex(ByteBuffer v,int p) {
        float[] a=new float[5];for(int j=0;j<5;j++) {
            a[j]=v.getFloat(p*48+j*4);
            if(!Float.isFinite(a[j]))throw new IllegalArgumentException("Nonfinite package vertex");
        }
        return a;
    }
    private static float[] unit(float x,float y,float z,float fx,float fy,float fz) {
        if(!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
            throw new IllegalArgumentException("Nonfinite package attribute");
        double len=Math.sqrt((double)x*x+(double)y*y+(double)z*z);
        return len<1e-10?new float[]{fx,fy,fz}:new float[]{(float)(x/len),(float)(y/len),(float)(z/len)};
    }
}
