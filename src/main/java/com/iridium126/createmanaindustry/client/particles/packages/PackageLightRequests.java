package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;

/** Numeric, world-scoped prefetch requests. Never contains a particle index or gameplay identity. */
public final class PackageLightRequests {
    public static final int BUCKETS=1024,MAX_REQUESTS=256,HEADER_BYTES=16,BYTES=HEADER_BYTES+MAX_REQUESTS*16;
    private final PackageCollisionCache.Section[] scratch=new PackageCollisionCache.Section[MAX_REQUESTS];
    /** Validate the complete bounded record before invoking any owner-thread callback. */
    public int consume(ByteBuffer data,Consumer<PackageCollisionCache.Section> consumer) {
        if(data.remaining()!=BYTES)throw new IllegalArgumentException("Light request snapshot size");
        var view=data.duplicate().order(ByteOrder.nativeOrder());int p=view.position(),total=view.getInt(p);
        if(total<0 || total>BUCKETS || view.getInt(p+4)!=0 || view.getInt(p+8)!=0 || view.getInt(p+12)!=0)
            throw new IllegalArgumentException("Light request count/header");
        int count=Math.min(total,MAX_REQUESTS);
        for(int i=0;i<count;i++) {
            int q=p+HEADER_BYTES+16*i,x=view.getInt(q),y=view.getInt(q+4),z=view.getInt(q+8);
            if(!coordinate(x) || !coordinate(y) || !coordinate(z) || view.getInt(q+12)!=0)
                throw new IllegalArgumentException("Light request section coordinate");
            scratch[i]=new PackageCollisionCache.Section(x,y,z);
        }
        try{for(int i=0;i<count;i++)consumer.accept(scratch[i]);}finally{java.util.Arrays.fill(scratch,0,count,null);}
        return total;
    }
    public static boolean coordinate(int section){return section>=Integer.MIN_VALUE/16 && section<Integer.MAX_VALUE/16;}
}
