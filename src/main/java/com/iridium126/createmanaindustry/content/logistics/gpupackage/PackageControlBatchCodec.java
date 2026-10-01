package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.nio.ByteBuffer;
import java.util.List;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ServerboundPackagePacket;

/** Internal control batching, no pose/inventory data. Every row retains exact identity and lease.
 * Sorted indices and signed ID differences are lossless; generation/lease/revision reuse only
 * the previous row in this packet. No decoder state crosses a packet/region boundary. */
public final class PackageControlBatchCodec {
    public static final int MAX_RECORDS=256,MAX_BYTES=3+MAX_RECORDS*43;
    @FunctionalInterface public interface Visitor {
        void control(int action,int index,long id,long generation,long leaseEpoch,long revision);
    }
    public static boolean supported(int action){return action==ServerboundPackagePacket.PREPARED
            ||action==ServerboundPackagePacket.FINAL_READY||action==ServerboundPackagePacket.RELEASE
            ||action==ServerboundPackagePacket.VISIBLE_READY;}
    /** Caller sorts its borrowed baseline references; validate before touching the output. */
    public static void encode(ByteBuffer out,int action,List<PackageAuthorityRegion.Baseline> rows) {
        if(!supported(action)||rows.isEmpty()||rows.size()>MAX_RECORDS||out.remaining()<MAX_BYTES)
            throw new IllegalArgumentException("Package control batch bound/action");
        int before=-1;
        for(var row:rows){if(row==null||row.index()<=before||row.identity()==null||row.leaseEpoch()<=0||row.revision()<=0)
            throw new IllegalArgumentException("Package control batch row");before=row.index();}
        out.put((byte)action);put(out,rows.size());
        int previousIndex=-1;long previousId=0,generation=0,lease=0,revision=0;
        for(var row:rows) {
            put(out,previousIndex<0?row.index():(long)row.index()-previousIndex);previousIndex=row.index();
            long difference=row.identity().id()-previousId;put(out,(difference<<1)^(difference>>63));previousId=row.identity().id();
            int mask=(row.identity().generation()!=generation?1:0)|(row.leaseEpoch()!=lease?2:0)|(row.revision()!=revision?4:0);
            out.put((byte)mask);
            if((mask&1)!=0){generation=row.identity().generation();put(out,generation);}
            if((mask&2)!=0){lease=row.leaseEpoch();put(out,lease);}
            if((mask&4)!=0){revision=row.revision();put(out,revision);}
        }
    }
    /** Validate the entire body before any callback, including trailing bytes and varint overflow.
     * Two bounded passes avoid an intermediate object/identity array on the server thread. */
    public static int visitValidated(ByteBuffer in,Visitor visitor) {
        return visitValidated(in,MAX_RECORDS,visitor);
    }
    public static int visitValidated(ByteBuffer in,int maximumRecords,Visitor visitor) {
        if(maximumRecords<0)throw new IllegalArgumentException("Package control processing budget");
        if(in.remaining()>MAX_BYTES)throw new IllegalArgumentException("Package control batch size");
        int count=walk(in.duplicate(),null);
        if(count>maximumRecords)return 0;
        if(visitor!=null)walk(in.duplicate(),visitor);
        return count;
    }
    private static int walk(ByteBuffer in,Visitor visitor) {
        try {
            int action=Byte.toUnsignedInt(in.get());long quantity=get(in);
            if(!supported(action)||quantity<=0||quantity>MAX_RECORDS)throw new IllegalArgumentException("Package control batch count/action");
            int count=(int)quantity,previousIndex=-1;long previousId=0,generation=0,lease=0,revision=0;
            for(int row=0;row<count;row++) {
                long delta=get(in),index=previousIndex<0?delta:Math.addExact(previousIndex,delta);
                if(delta<0||index<0||index>Integer.MAX_VALUE||row>0&&delta==0)throw new IllegalArgumentException("Package control index");
                previousIndex=(int)index;
                long bits=get(in),difference=(bits>>>1)^-(bits&1),id=Math.addExact(previousId,difference);
                if(id<=0)throw new IllegalArgumentException("Package control identity");previousId=id;
                int mask=Byte.toUnsignedInt(in.get());if(mask>7||row==0&&mask!=7)throw new IllegalArgumentException("Package control fields");
                if((mask&1)!=0)generation=positive(in);
                if((mask&2)!=0)lease=positive(in);
                if((mask&4)!=0)revision=positive(in);
                if(visitor!=null)visitor.control(action,previousIndex,id,generation,lease,revision);
            }
            if(in.hasRemaining())throw new IllegalArgumentException("Trailing package controls");return count;
        }catch(java.nio.BufferUnderflowException|ArithmeticException invalid){throw new IllegalArgumentException("Truncated/overflowing package controls",invalid);}
    }
    private static long positive(ByteBuffer in){long value=get(in);if(value<=0)throw new IllegalArgumentException("Package control positive field");return value;}
    private static void put(ByteBuffer out,long bits){while((bits&~127L)!=0){out.put((byte)((bits&127)|128));bits>>>=7;}out.put((byte)bits);}
    private static long get(ByteBuffer in) {
        long value=0;
        for(int byteIndex=0;byteIndex<10;byteIndex++) {
            int next=Byte.toUnsignedInt(in.get());
            if(byteIndex==9&&(next&~1)!=0)throw new IllegalArgumentException("Package control varlong overflow");
            value|=(long)(next&127)<<(byteIndex*7);
            if((next&128)==0){if(byteIndex>0&&next==0)throw new IllegalArgumentException("Noncanonical package control varlong");return value;}
        }
        throw new IllegalArgumentException("Package control varlong length");
    }
    private PackageControlBatchCodec(){}
}
