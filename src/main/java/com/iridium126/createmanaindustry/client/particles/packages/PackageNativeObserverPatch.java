package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Copies native wire values, without CPU position decoding, quantization or pose merging.
 * Local identity is a client visual identity, not an authority lease or inventory identity.
 * Commands for the same member must be submitted in ordered, distinct GPU batches. */
public final class PackageNativeObserverPatch {
    public static final int BASELINE_BYTES=128,COMMAND_BYTES=64;
    public static final int MOVE=1,TELEPORT=2,MOTION=4,RELEASE=16,BASELINE=32;
    public static final long STREAM=1;
    private PackageNativeObserverPatch() {}
    private static int prepare(ByteBuffer out,int bytes) {
        if(out==null||!out.isDirect()||out.order()!=ByteOrder.nativeOrder()||out.remaining()<bytes)
            throw new IllegalArgumentException("Native observer command layout");
        int p=out.position();for(int j=0;j<bytes;j+=8)out.putLong(p+j,0);return p;
    }
    private static void namespace(int local,long epoch,long sequence,float receipt) {
        if(local<0||epoch<=0||sequence<0||!Float.isFinite(receipt)||receipt<0)
            throw new IllegalArgumentException("Native observer namespace/clock");
    }
    public static void baseline(ByteBuffer out,int local,int entityId,long visualId,long generation,long epoch,long sequence,
                                double x,double y,double z,double vx,double vy,double vz,float width,float height,float yaw,
                                boolean onGround,float receipt) {
        namespace(local,epoch,sequence,receipt);
        if(visualId<=0||generation<=0||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)
                ||!Double.isFinite(vx)||!Double.isFinite(vy)||!Double.isFinite(vz)
                ||!Float.isFinite(width)||!Float.isFinite(height)||!Float.isFinite(yaw)||width<=0||height<=0)
            throw new IllegalArgumentException("Native observer baseline");
        int p=prepare(out,BASELINE_BYTES);
        out.putLong(p,visualId).putLong(p+8,generation).putLong(p+16,epoch).putLong(p+24,STREAM);
        out.putInt(p+32,local).putInt(p+36,entityId).putInt(p+40,BASELINE).putInt(p+44,onGround?1:0);
        out.putDouble(p+48,x).putDouble(p+56,y).putDouble(p+64,z);
        out.putDouble(p+72,vx).putDouble(p+80,vy).putDouble(p+88,vz);
        out.putFloat(p+96,width).putFloat(p+100,height).putFloat(p+104,yaw).putFloat(p+108,receipt);
        out.putLong(p+112,sequence);out.position(p+BASELINE_BYTES);
    }
    private static int command(ByteBuffer out,int local,int entityId,long epoch,long sequence,int op,int flags,float receipt) {
        namespace(local,epoch,sequence,receipt);int p=prepare(out,COMMAND_BYTES);
        out.putLong(p,epoch).putLong(p+8,sequence);
        out.putInt(p+16,local).putInt(p+20,entityId).putInt(p+24,op).putInt(p+28,flags);
        out.putFloat(p+56,receipt);out.position(p+COMMAND_BYTES);return p;
    }
    public static void move(ByteBuffer out,int local,int entityId,long epoch,long sequence,short dx,short dy,short dz,
                            boolean hasPosition,boolean hasRotation,byte yaw,boolean onGround,float receipt) {
        int p=command(out,local,entityId,epoch,sequence,MOVE,(onGround?1:0)|(hasPosition?2:0)|(hasRotation?4:0)|((yaw&255)<<8),receipt);
        out.putInt(p+32,dx).putInt(p+36,dy).putInt(p+40,dz);
    }
    public static void teleport(ByteBuffer out,int local,int entityId,long epoch,long sequence,double x,double y,double z,
                                byte yaw,boolean onGround,float receipt) {
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("Native teleport");
        int p=command(out,local,entityId,epoch,sequence,TELEPORT,(onGround?1:0)|((yaw&255)<<8),receipt);
        out.putDouble(p+32,x).putDouble(p+40,y).putDouble(p+48,z);
    }
    /** Raw /8000 protocol units. Create lerpMotion averages the previous and received velocity. */
    public static void motion(ByteBuffer out,int local,int entityId,long epoch,long sequence,int vx,int vy,int vz,float receipt) {
        if(vx<Short.MIN_VALUE||vx>Short.MAX_VALUE||vy<Short.MIN_VALUE||vy>Short.MAX_VALUE||vz<Short.MIN_VALUE||vz>Short.MAX_VALUE)
            throw new IllegalArgumentException("Native motion wire range");
        int p=command(out,local,entityId,epoch,sequence,MOTION,0,receipt);
        out.putInt(p+32,vx).putInt(p+36,vy).putInt(p+40,vz);
    }
    public static void release(ByteBuffer out,int local,int entityId,long epoch,long sequence,float receipt) {
        command(out,local,entityId,epoch,sequence,RELEASE,0,receipt);
    }
}
