package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageDeltaCodec;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageBatchDeltaCodec;

/** Allocation-free wire encoding of a bounded immutable GPU fragment, on a worker thread. */
final class PackageDeltaRecords {
    static final int BYTES=64,BATCH=512,MAX_WIRE_BYTES=17410;
    static final int MAX_BATCH_WIRE_BYTES=PackageBatchDeltaCodec.MAX_RECORD_BYTES*BATCH+2;
    private static final ThreadLocal<PackageBatchDeltaCodec.Writer> WRITERS=ThreadLocal.withInitial(PackageBatchDeltaCodec.Writer::new);
    private PackageDeltaRecords() {}
    static int encode(ByteBuffer raw,int count,ByteBuffer wire,long[] order,long[] identities,int[] localIds,int identityCount) {
        var input=ordered(raw,count,order,identities,localIds,identityCount);int start=input.position();
        wire.clear();varint(wire,count);int previous=-1;
        for(int i=0;i<count;i++) {
            int id=(int)(order[i]>>>32),p=start+(int)order[i]*BYTES;
            varint(wire,id-(previous+1));previous=id;
            int mask=input.getInt(p+28)!=0?PackageDeltaCodec.RELEASE:input.getInt(p+24);wire.put((byte)mask);
            if((mask&1)!=0)for(int v=32;v<=40;v+=4)signed(wire,input.getInt(p+v));
            if((mask&2)!=0)for(int v=48;v<=56;v+=4)signed(wire,input.getInt(p+v));
            if((mask&4)!=0)signed(wire,input.getInt(p+60));
            if((mask&8)!=0)varint(wire,input.getInt(p+44));
        }
        int length=wire.position();wire.flip();return length;
    }
    static int encodeBatch(ByteBuffer raw,int count,ByteBuffer wire,long[] order,long[] identities,int[] localIds,int identityCount) {
        var input=ordered(raw,count,order,identities,localIds,identityCount);int start=input.position();wire.clear();
        var writer=WRITERS.get().reset(wire,count);
        for(int i=0;i<count;i++) {
            int p=start+(int)order[i]*BYTES,mask=input.getInt(p+28)!=0?PackageDeltaCodec.RELEASE:input.getInt(p+24);
            writer.entry((int)(order[i]>>>32),mask,input.getInt(p+32),input.getInt(p+36),input.getInt(p+40),
                    input.getInt(p+48),input.getInt(p+52),input.getInt(p+56),input.getInt(p+60),input.getInt(p+44));
        }
        writer.finish();int length=wire.position();wire.flip();return length;
    }
    private static ByteBuffer ordered(ByteBuffer raw,int count,long[] order,long[] identities,int[] localIds,int identityCount) {
        if(count<=0 || count>BATCH || raw.remaining()!=count*BYTES || order.length<count)throw new IllegalArgumentException("Delta fragment layout");
        var input=raw.duplicate().order(ByteOrder.nativeOrder());int start=input.position();
        for(int i=0;i<count;i++) {
            int p=start+i*BYTES,candidate=input.getInt(p+16),id=input.getInt(p+20);
            int mask=input.getInt(p+24),release=input.getInt(p+28);
            if(candidate<0 || candidate>=identityCount || id!=localIds[candidate]
                    || input.getLong(p)!=identities[candidate*2] || input.getLong(p+8)!=identities[candidate*2+1]
                    || (release==0?!PackageDeltaCodec.validMask(mask) || mask==PackageDeltaCodec.RELEASE:release!=1 || mask!=0))
                throw new IllegalArgumentException("GPU delta identity/mask/status");
            if(release==0) {
                for(int v=48;v<=60;v+=4)if(input.getInt(p+v)<Short.MIN_VALUE || input.getInt(p+v)>Short.MAX_VALUE)
                    throw new IllegalArgumentException("GPU delta short overflow");
                if((input.getInt(p+44)&~3)!=0)throw new IllegalArgumentException("GPU delta flags");
            }
            order[i]=((long)id<<32)|(i&0xffffffffL);
        }
        Arrays.sort(order,0,count);int previous=-1;
        for(int i=0;i<count;i++) {
            int id=(int)(order[i]>>>32);
            if(id<=previous)throw new IllegalArgumentException("Duplicate GPU wire identity");
            previous=id;
        }
        return input;
    }
    private static void signed(ByteBuffer b,int value){varint(b,(value<<1)^(value>>31));}
    private static void varint(ByteBuffer b,int value){while((value&~127)!=0){b.put((byte)((value&127)|128));value>>>=7;}b.put((byte)value);}
}
