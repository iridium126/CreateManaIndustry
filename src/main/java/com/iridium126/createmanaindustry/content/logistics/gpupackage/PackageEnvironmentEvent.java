package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.nio.*;
import java.util.*;

/** Bounded, exact-lifecycle environmental replay. Inventory is never part of this uplink. */
public record PackageEnvironmentEvent(PackageLease.Identity identity,long lease,int index,long revision,long first,long through,List<Sample> samples) {
    /** GPU block and body coordinates share the physics origin. Both wire coordinates must
     * share the authority region origin; the server adds that origin exactly once. */
    public static byte[] regionPayload(ByteBuffer raw,int ox,int oy,int oz,PackageRegion region) {
        if(raw.remaining()!=1024)throw new IllegalArgumentException("Environment event length");
        var copy=ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);copy.put(raw.duplicate());
        long count=Integer.toUnsignedLong(copy.getInt(24))-Integer.toUnsignedLong(copy.getInt(28));
        if(count<1||count>20)throw new IllegalArgumentException("Environment event window");
        int dx=Math.toIntExact((long)ox-(long)region.originX()),dy=Math.toIntExact((long)oy-(long)region.originY()),dz=Math.toIntExact((long)oz-(long)region.originZ());
        for(int n=0;n<count;n++) {
            int p=64+n*48;
            copy.putInt(p,Math.addExact(copy.getInt(p),dx)).putInt(p+4,Math.addExact(copy.getInt(p+4),dy)).putInt(p+8,Math.addExact(copy.getInt(p+8),dz));
            copy.putFloat(p+32,(float)((double)copy.getFloat(p+32)+dx));
            copy.putFloat(p+36,(float)((double)copy.getFloat(p+36)+dy));
            copy.putFloat(p+40,(float)((double)copy.getFloat(p+40)+dz));
        }
        return copy.array();
    }
    public record Sample(long step,int contact,int x,int y,int z,float px,float py,float pz,int fireTicks,float health,int ticks) {
        public Sample(long step,int contact,int x,int y,int z,float px,float py,float pz,int fireTicks,float health){this(step,contact,x,y,z,px,py,pz,fireTicks,health,1);}
        public net.minecraft.core.BlockPos block(PackageRegion region){
            return net.minecraft.core.BlockPos.containing(region.originX()+x,region.originY()+y,region.originZ()+z);
        }
    }
    public static PackageEnvironmentEvent decode(byte[] payload){
        return decode(ByteBuffer.wrap(payload));
    }
    /** Decode the server-owned packet view without cloning its bounded payload. */
    public static PackageEnvironmentEvent decode(ByteBuffer payload){
        if(payload.remaining()!=1024)throw new IllegalArgumentException("Environment event length");
        var b=payload.slice().order(ByteOrder.LITTLE_ENDIAN);
        var identity=new PackageLease.Identity(b.getLong(0),b.getLong(8));long lease=b.getLong(16);
        long end=Integer.toUnsignedLong(b.getInt(24)),first=Integer.toUnsignedLong(b.getInt(28));
        int index=b.getInt(40)-1;long revision=b.getLong(56);
        if(lease<=0||index<0||revision<=0||end<=first||end-first>20)throw new IllegalArgumentException("Environment event window");
        var samples=new ArrayList<Sample>();long previous=-1;
        for(int i=0;i<end-first;i++){
            int p=64+i*48;int contact=b.getInt(p+12);long step=b.getLong(p+16);
            int fire=b.getInt(p+24);float health=b.getFloat(p+28),x=b.getFloat(p+32),y=b.getFloat(p+36),z=b.getFloat(p+40);
            int ticks=Math.max(1,b.getInt(p+44));
            if(ticks>10000||b.getInt(p+44)<0||step-ticks<previous||step<ticks||(contact!=0&&contact!=1&&contact!=2&&contact!=4&&contact!=8&&contact!=16)||fire<0||fire>72000
                    ||!Float.isFinite(health)||health<0||health>1024||!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z))
                throw new IllegalArgumentException("Invalid environment sample");
            previous=step;samples.add(new Sample(step,contact,b.getInt(p),b.getInt(p+4),b.getInt(p+8),x,y,z,fire,health,ticks));
        }
        return new PackageEnvironmentEvent(identity,lease,index,revision,first,end,List.copyOf(samples));
    }
}
