package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.nio.*;
import java.util.*;

/** Bounded, exact-lifecycle environmental replay. Inventory is never part of this uplink. */
public record PackageEnvironmentEvent(PackageLease.Identity identity,long lease,int index,long revision,long first,long through,List<Sample> samples) {
    public record Sample(long step,int contact,int x,int y,int z,float px,float py,float pz,int fireTicks,float health) {
        public net.minecraft.core.BlockPos block(PackageRegion region){
            return net.minecraft.core.BlockPos.containing(region.originX()+x,region.originY()+y,region.originZ()+z);
        }
    }
    public static PackageEnvironmentEvent decode(byte[] payload){
        if(payload.length!=1024)throw new IllegalArgumentException("Environment event length");
        var b=ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        var identity=new PackageLease.Identity(b.getLong(0),b.getLong(8));long lease=b.getLong(16);
        long end=Integer.toUnsignedLong(b.getInt(24)),first=Integer.toUnsignedLong(b.getInt(28));
        int index=b.getInt(40)-1;long revision=b.getLong(56);
        if(lease<=0||index<0||revision<=0||end<=first||end-first>20)throw new IllegalArgumentException("Environment event window");
        var samples=new ArrayList<Sample>();long previous=-1;
        for(int i=0;i<end-first;i++){
            int p=64+i*48;int contact=b.getInt(p+12);long step=b.getLong(p+16);
            int fire=b.getInt(p+24);float health=b.getFloat(p+28),x=b.getFloat(p+32),y=b.getFloat(p+36),z=b.getFloat(p+40);
            if(step<=previous||step<1||(contact!=0&&contact!=1&&contact!=2&&contact!=4&&contact!=8&&contact!=16)||fire<0||fire>72000
                    ||!Float.isFinite(health)||health<0||health>1024||!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z))
                throw new IllegalArgumentException("Invalid environment sample");
            previous=step;samples.add(new Sample(step,contact,b.getInt(p),b.getInt(p+4),b.getInt(p+8),x,y,z,fire,health));
        }
        return new PackageEnvironmentEvent(identity,lease,index,revision,first,end,List.copyOf(samples));
    }
}
