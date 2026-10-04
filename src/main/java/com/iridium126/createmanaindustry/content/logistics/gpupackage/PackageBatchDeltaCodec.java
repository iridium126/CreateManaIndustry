package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Lossless packet-local prediction; no dependency on a previous packet or unacknowledged pose.
 * Sorted consecutive IDs are implicit. Header low five bits are the original field mask;
 * high three bits select position axes changed from the preceding POSITION record in THIS
 * packet (initial predictor is zero). Velocity uses a separate three-bit axis selector.
 * Selected axis values are signed differences; unchanged axes use the packet predictor.
 * Header zero escapes a positive ID gap followed by a nonzero header. Release has no pose.
 * All predictors reset for each packet. Identity/epoch/revision stay in the enclosing envelope.
 * Velocity predictors are signed 32-bit values for the negotiated fast-contraption range;
 * position and yaw quantization, simulation cadence and ACK semantics remain unchanged. */
public final class PackageBatchDeltaCodec {
    public static final int MAX_RECORD_BYTES=47;
    private PackageBatchDeltaCodec() {}

    /** Reusable worker encoder; primitive entry method avoids one Java object per GPU record. */
    public static final class Writer {
        private ByteBuffer out;
        private int count,written,previous,x,y,z,vx,vy,vz,yaw;
        public Writer reset(ByteBuffer output,int count) {
            if(count<0 || count>PackageDeltaCodec.MAX_ENTRIES)throw new IllegalArgumentException("Batch delta count");
            out=java.util.Objects.requireNonNull(output);this.count=count;written=0;previous=-1;
            x=y=z=vx=vy=vz=yaw=0;unsigned(out,count);return this;
        }
        public void entry(int id,int mask,int nx,int ny,int nz,int nvx,int nvy,int nvz,int nyaw,int flags) {
            if(out==null || written>=count || id<=previous || !PackageDeltaCodec.validMask(mask))
                throw new IllegalArgumentException("Batch delta ID/mask/count");
            if((mask&PackageDeltaCodec.YAW)!=0 && !shortValue(nyaw))throw new IllegalArgumentException("Batch delta short");
            int axes=(mask&PackageDeltaCodec.POSITION)!=0?axes(x,y,z,nx,ny,nz):0;
            if(id!=previous+1){out.put((byte)0);unsigned(out,id-(previous+1));}
            out.put((byte)(mask | axes<<5));
            if((axes&1)!=0)signed(out,(long)nx-x);
            if((axes&2)!=0)signed(out,(long)ny-y);
            if((axes&4)!=0)signed(out,(long)nz-z);
            if((mask&PackageDeltaCodec.POSITION)!=0){x=nx;y=ny;z=nz;}
            if((mask&PackageDeltaCodec.VELOCITY)!=0) {
                int velocityAxes=axes(vx,vy,vz,nvx,nvy,nvz);out.put((byte)velocityAxes);
                if((velocityAxes&1)!=0)signed(out,(long)nvx-vx);
                if((velocityAxes&2)!=0)signed(out,(long)nvy-vy);
                if((velocityAxes&4)!=0)signed(out,(long)nvz-vz);
                vx=nvx;vy=nvy;vz=nvz;
            }
            if((mask&PackageDeltaCodec.YAW)!=0){signed(out,(long)nyaw-yaw);yaw=nyaw;}
            if((mask&PackageDeltaCodec.FLAGS)!=0)unsigned(out,Integer.toUnsignedLong(flags));
            previous=id;written++;
        }
        public void finish(){if(out==null || written!=count)throw new IllegalStateException("Incomplete batch delta");out=null;}
    }
    public static void encode(ByteBuffer out,List<PackageDeltaCodec.Entry> entries) {
        var writer=new Writer().reset(out,entries.size());
        for(var entry:entries){var v=entry.value();writer.entry(entry.id(),entry.mask(),v.x(),v.y(),v.z(),v.vx(),v.vy(),v.vz(),v.yaw(),v.flags());}
        writer.finish();
    }
    public static List<PackageDeltaCodec.Entry> decode(ByteBuffer in) {
        return decode(in,PackageDeltaCodec.MAX_ENTRIES);
    }
    public static List<PackageDeltaCodec.Entry> decode(ByteBuffer in,int maximumEntries) {
        return decodeInto(in,maximumEntries,new ArrayList<>());
    }
    /** Decode into a caller-owned list so a serialized server receiver can reuse its storage. */
    public static List<PackageDeltaCodec.Entry> decodeInto(ByteBuffer in,int maximumEntries,List<PackageDeltaCodec.Entry> result) {
        if(maximumEntries<0 || maximumEntries>PackageDeltaCodec.MAX_ENTRIES)throw new IllegalArgumentException("Batch delta bound");
        java.util.Objects.requireNonNull(result).clear();
        int count=(int)readUnsigned(in,PackageDeltaCodec.MAX_ENTRIES);
        if(count>maximumEntries || count>in.remaining())throw new IllegalArgumentException("Batch delta count/body");
        if(result instanceof ArrayList<?> array)array.ensureCapacity(count);
        long previous=-1;
        int x=0,y=0,z=0,vx=0,vy=0,vz=0,yaw=0;
        for(int i=0;i<count;i++) {
            int header=Byte.toUnsignedInt(in.get());long gap=0;
            if(header==0) {
                gap=readUnsigned(in,Integer.MAX_VALUE);if(gap==0)throw new IllegalArgumentException("Redundant batch ID escape");
                header=Byte.toUnsignedInt(in.get());
            }
            long id=previous+1+gap;if(id>Integer.MAX_VALUE)throw new IllegalArgumentException("Batch delta ID overflow");
            int mask=header&31,axes=header>>>5;
            if(!PackageDeltaCodec.validMask(mask) || axes!=0 && (mask&PackageDeltaCodec.POSITION)==0)
                throw new IllegalArgumentException("Batch delta header");
            if((axes&1)!=0)x=integer(x,signed(in));
            if((axes&2)!=0)y=integer(y,signed(in));
            if((axes&4)!=0)z=integer(z,signed(in));
            if((mask&PackageDeltaCodec.VELOCITY)!=0) {
                int velocityAxes=Byte.toUnsignedInt(in.get());
                if((velocityAxes&~7)!=0)throw new IllegalArgumentException("Batch delta velocity axes");
                if((velocityAxes&1)!=0)vx=integer(vx,signed(in));
                if((velocityAxes&2)!=0)vy=integer(vy,signed(in));
                if((velocityAxes&4)!=0)vz=integer(vz,signed(in));
            }
            if((mask&PackageDeltaCodec.YAW)!=0)yaw=shortSum(yaw,signed(in));
            int flags=(mask&PackageDeltaCodec.FLAGS)!=0?(int)readUnsigned(in,0xffffffffL):0;
            // Unselected fields are zero, as in the old codec; the server's exact field merge
            // retains the acknowledged per-member values, never this packet-local predictor.
            result.add(new PackageDeltaCodec.Entry((int)id,mask,new PackageDeltaCodec.Quantized(
                    (mask&1)!=0?x:0,(mask&1)!=0?y:0,(mask&1)!=0?z:0,
                    (mask&2)!=0?vx:0,(mask&2)!=0?vy:0,(mask&2)!=0?vz:0,
                    (short)((mask&4)!=0?yaw:0),flags)));
            previous=id;
        }
        return result;
    }
    private static int axes(int x,int y,int z,int nx,int ny,int nz){return (nx!=x?1:0)|(ny!=y?2:0)|(nz!=z?4:0);}
    private static boolean shortValue(int value){return value>=Short.MIN_VALUE && value<=Short.MAX_VALUE;}
    private static int integer(int previous,long difference) {
        long value=previous+difference;
        if(value<Integer.MIN_VALUE || value>Integer.MAX_VALUE)throw new IllegalArgumentException("Batch delta position overflow");
        return (int)value;
    }
    private static int shortSum(int previous,long difference) {
        int value=integer(previous,difference);if(!shortValue(value))throw new IllegalArgumentException("Batch delta short overflow");return value;
    }
    private static void signed(ByteBuffer out,long value){unsigned(out,(value<<1)^(value>>63));}
    private static long signed(ByteBuffer in){long value=readUnsigned(in,0x1fffffffeL);return (value>>>1)^-(value&1);}
    private static void unsigned(ByteBuffer out,long value) {
        do {int b=(int)(value&127);value>>>=7;out.put((byte)(value==0?b:b|128));}while(value!=0);
    }
    private static long readUnsigned(ByteBuffer in,long maximum) {
        long value=0;
        for(int i=0;i<5;i++) {
            int b=Byte.toUnsignedInt(in.get());value|=(long)(b&127)<<(i*7);
            if((b&128)==0) {
                if(value>maximum || i>0 && (b&127)==0)throw new IllegalArgumentException("Batch delta varint overflow/noncanonical");
                return value;
            }
        }
        throw new IllegalArgumentException("Batch delta varint too long");
    }
}
