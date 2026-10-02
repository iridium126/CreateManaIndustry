package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;

/** Bounded numeric requests produced by the GPU before a free package reaches uncaptured world. */
public final class PackageCollisionRequests {
    public static final int MAX_REQUESTS=4096,MAX_TOUCH_WORDS=128,MAX_BODIES=131072,HEADER_BYTES=16;
    public static final int TOUCH_OFFSET=HEADER_BYTES+MAX_REQUESTS*16,TOUCH_BYTES=MAX_TOUCH_WORDS*4;
    public static final int BYTES=TOUCH_OFFSET+TOUCH_BYTES;
    public interface Usage {
        void begin(long tableVersion);
        void row(long tableVersion,int row);
    }
    private final PackageCollisionCache.Section[] scratch=new PackageCollisionCache.Section[MAX_REQUESTS];
    private final int[] hashX=new int[MAX_REQUESTS*2],hashY=new int[MAX_REQUESTS*2],hashZ=new int[MAX_REQUESTS*2],stamps=new int[MAX_REQUESTS*2];
    private int stamp;

    /** Validate the entire bounded header and retained prefix before queuing owner-thread work. */
    public int consume(ByteBuffer data,Consumer<PackageCollisionCache.Section> consumer) {
        return consume(data,consumer,null);
    }
    /** Touch rows are delivered before new requests can evict entries from the scanned table. */
    public int consume(ByteBuffer data,Consumer<PackageCollisionCache.Section> consumer,Usage usage) {
        if(data.remaining()!=BYTES)throw new IllegalArgumentException("Collision request snapshot size");
        var view=data.duplicate().order(ByteOrder.nativeOrder());int p=view.position();
        int total=view.getInt(p),overflow=view.getInt(p+4);long tableVersion=view.getLong(p+8);
        if(total<0 || overflow<0)
            throw new IllegalArgumentException("Collision request header");
        int count=Math.min(total,MAX_REQUESTS),unique=0;stamp++;
        if(stamp==0){java.util.Arrays.fill(stamps,0);stamp=1;}
        for(int i=0;i<count;i++) {
            int q=p+HEADER_BYTES+16*i,x=view.getInt(q),y=view.getInt(q+4),z=view.getInt(q+8);
            if(!PackageLightRequests.coordinate(x)||!PackageLightRequests.coordinate(y)||!PackageLightRequests.coordinate(z)
                    || view.getInt(q+12)!=1)throw new IllegalArgumentException("Collision request section record");
        }
        if(usage!=null) {
            usage.begin(tableVersion);
            for(int word=0;word<MAX_TOUCH_WORDS;word++) {
                int bits=view.getInt(p+TOUCH_OFFSET+word*4);
                while(bits!=0) {
                    int bit=Integer.numberOfTrailingZeros(bits);usage.row(tableVersion,word*32+bit);bits&=bits-1;
                }
            }

        }
        for(int i=0;i<count;i++) {
            int q=p+HEADER_BYTES+16*i,x=view.getInt(q),y=view.getInt(q+4),z=view.getInt(q+8);
            int bucket=(x*0x8da6b343 ^ y*0xd8163841 ^ z*0xcb1ab31f)&(stamps.length-1);
            while(stamps[bucket]==stamp) {
                if(hashX[bucket]==x&&hashY[bucket]==y&&hashZ[bucket]==z)break;
                bucket=(bucket+1)&(stamps.length-1);
            }
            if(stamps[bucket]==stamp)continue;
            stamps[bucket]=stamp;hashX[bucket]=x;hashY[bucket]=y;hashZ[bucket]=z;
            scratch[unique++]=new PackageCollisionCache.Section(x,y,z);
        }
        try{for(int i=0;i<unique;i++)consumer.accept(scratch[i]);}
        finally{java.util.Arrays.fill(scratch,0,unique,null);}
        return total;
    }
}
