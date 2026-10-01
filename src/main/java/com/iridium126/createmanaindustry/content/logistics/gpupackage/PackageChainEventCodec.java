package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Internal, bounded node-candidate protocol. It never conveys permission to change inventory.
 * Epoch, table namespace, flight stamp and reliable transaction sequence belong to the envelope.
 * Stable identity and track revision remain exact; progress floats retain their original bits. */
public final class PackageChainEventCodec {
    public static final int RECORD_BYTES=64,BATCH=256,MAX_WIRE_BYTES=16386;
    public static final int FALLBACK=0x80000000;
    private PackageChainEventCodec() {}

    public static void validate(ByteBuffer raw,int position) {
        long id=raw.getLong(position),generation=raw.getLong(position+8),revision=raw.getLong(position+20);
        int candidate=raw.getInt(position+16),step=raw.getInt(position+28),actual=raw.getInt(position+32),ahead=raw.getInt(position+36);
        int track=raw.getInt(position+40),flags=raw.getInt(position+44);
        boolean fallback=(flags&FALLBACK)!=0;
        if(id<=0 || generation<=0 || candidate<0 || candidate>=131072 || track<0 || track>=131072 || step==0
                || (flags&~(FALLBACK|3))!=0 || fallback && flags!=FALLBACK || revision<0 || !fallback && revision==0
                || !fallback && (actual|ahead)==0 || fallback && (actual|ahead)!=0 || raw.getInt(position+60)!=0)
            throw new IllegalArgumentException("Chain event identity/envelope/masks");
        for(int offset=48;offset<60;offset+=4)
            if(!Float.isFinite(raw.getFloat(position+offset)))throw new IllegalArgumentException("Chain event non-finite progress");
        if(raw.getFloat(position+48)<0 || raw.getFloat(position+52)<0)throw new IllegalArgumentException("Chain event negative progress");
    }

    /** Allocation-free worker encoding. Input position is preserved. Output is flipped on success. */
    public static void encode(ByteBuffer raw,int count,ByteBuffer wire,long[] order) {
        if(count<=0 || count>BATCH || raw.remaining()!=count*RECORD_BYTES || order.length<count || wire.capacity()<MAX_WIRE_BYTES)
            throw new IllegalArgumentException("Chain event batch layout");
        var input=raw.duplicate().order(ByteOrder.nativeOrder());int start=input.position();
        for(int i=0;i<count;i++) {
            int p=start+i*RECORD_BYTES;validate(input,p);
            order[i]=((long)input.getInt(p+16)<<32)|(i&0xffffffffL);
        }
        Arrays.sort(order,0,count);
        for(int i=1;i<count;i++)if((order[i]>>>32)==(order[i-1]>>>32))throw new IllegalArgumentException("Duplicate chain candidate");
        wire.clear();put(wire,count);int previous=-1;
        for(int i=0;i<count;i++) {
            int candidate=(int)(order[i]>>>32),p=start+(int)order[i]*RECORD_BYTES;
            put(wire,candidate-(previous+1));previous=candidate;
            put(wire,input.getLong(p));put(wire,input.getLong(p+8));put(wire,input.getLong(p+20));
            put(wire,Integer.toUnsignedLong(input.getInt(p+28)));
            put(wire,Integer.toUnsignedLong(input.getInt(p+32)));put(wire,Integer.toUnsignedLong(input.getInt(p+36)));
            put(wire,input.getInt(p+40));int flags=input.getInt(p+44);wire.put((byte)(flags==FALLBACK?4:flags));
            // Network byte order is fixed, independently of native SSBO byte order.
            for(int offset=48;offset<60;offset+=4) {
                int bits=input.getInt(p+offset);for(int shift=24;shift>=0;shift-=8)wire.put((byte)(bits>>>shift));
            }
        }
        wire.flip();
    }

    /** Server-side bounded decode into caller-owned storage; source position is preserved.
     * A rejected batch must never be submitted to gameplay callbacks. */
    public static int decode(ByteBuffer bytes,ByteBuffer records) {
        if(!bytes.hasRemaining() || bytes.remaining()>MAX_WIRE_BYTES)throw new IllegalArgumentException("Chain event wire limit");
        var in=bytes.duplicate();long size;
        try{size=get(in,2,14);}catch(java.nio.BufferUnderflowException truncated){throw new IllegalArgumentException("Truncated chain event count",truncated);}
        if(size<=0 || size>BATCH || records.remaining()<size*RECORD_BYTES)throw new IllegalArgumentException("Chain event decode bounds");
        var out=records.duplicate().order(ByteOrder.nativeOrder());int start=out.position(),previous=-1;
        try {
            for(int i=0;i<size;i++) {
                long candidate=(long)previous+1+get(in,3,17);
                if(candidate>=131072)throw new IllegalArgumentException("Chain candidate overflow");
                previous=(int)candidate;int p=start+i*RECORD_BYTES;
                out.putLong(p,get(in,9,63)).putLong(p+8,get(in,9,63)).putInt(p+16,previous).putLong(p+20,get(in,9,63));
                out.putInt(p+28,(int)get(in,5,32)).putInt(p+32,(int)get(in,5,32)).putInt(p+36,(int)get(in,5,32));
                out.putInt(p+40,(int)get(in,3,17));int flags=Byte.toUnsignedInt(in.get());
                if(flags>4)throw new IllegalArgumentException("Chain event wire flags");out.putInt(p+44,flags==4?FALLBACK:flags);
                for(int offset=48;offset<60;offset+=4) {
                    int bits=0;for(int b=0;b<4;b++)bits=(bits<<8)|Byte.toUnsignedInt(in.get());out.putInt(p+offset,bits);
                }
                out.putInt(p+60,0);validate(out,p);
            }
            if(in.hasRemaining())throw new IllegalArgumentException("Trailing chain event bytes");
        }catch(java.nio.BufferUnderflowException truncated){throw new IllegalArgumentException("Truncated chain event",truncated);}
        records.position(start+(int)size*RECORD_BYTES);return (int)size;
    }
    private static void put(ByteBuffer bytes,long value) {
        while((value&~127L)!=0){bytes.put((byte)((value&127)|128));value>>>=7;}bytes.put((byte)value);
    }
    private static long get(ByteBuffer bytes,int maximum,int bits) {
        long result=0;
        for(int i=0;i<maximum;i++) {
            int next=Byte.toUnsignedInt(bytes.get()),payload=next&127,available=bits-i*7;
            if(available<7 && payload>=(1<<available))throw new IllegalArgumentException("Chain event varint overflow");
            result|=(long)payload<<(i*7);
            if((next&128)==0) {
                if(i>0 && payload==0)throw new IllegalArgumentException("Non-canonical chain event varint");
                return result;
            }
        }
        throw new IllegalArgumentException("Malformed chain event varint");
    }
}
