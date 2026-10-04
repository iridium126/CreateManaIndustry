package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.ArrayList;

/** Bounded region-local wire records. Epoch/generation belong to the enclosing region baseline. */
public final class PackageDeltaCodec {
    public static final int POSITION = 1, VELOCITY = 2, YAW = 4, FLAGS = 8;
    public static final int RELEASE = 16;
    public static final int MAX_ENTRIES = 2048;
    public static final double POSITION_SCALE = 4096;
    // RPM-driven Create contraptions can impart velocities far above vanilla terminal
    // speed. Keep velocity in signed-varint fields (server bound: 2048 blocks/s), with a
    // 1/16 block/s step.
    public static final float VELOCITY_SCALE = 16;
    public record Quantized(int x, int y, int z, int vx, int vy, int vz, short yaw, int flags) {}
    public record Entry(int id, int mask, Quantized value) {
        public Entry {
            if (id < 0 || !validMask(mask) || value == null)
                throw new IllegalArgumentException("Invalid delta");
        }
    }
    private PackageDeltaCodec() {}
    public static boolean validMask(int mask){return mask==RELEASE || mask>0 && (mask&~15)==0;}
    public static Quantized quantize(PackageLease.Pose p, double ox, double oy, double oz, int flags) {
        return new Quantized(position(p.x() - ox), position(p.y() - oy), position(p.z() - oz),
                velocity(p.vx()), velocity(p.vy()), velocity(p.vz()),
                (short) Math.round((p.yaw() % 360f) * (65536.0 / 360)), flags);
    }
    private static int position(double v) {
        double scaled = Math.rint(v * POSITION_SCALE);
        if (!Double.isFinite(scaled) || scaled < Integer.MIN_VALUE || scaled > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Position requires new region origin");
        return (int) scaled;
    }
    private static int velocity(float v) {
        long scaled = Math.round((double) v * VELOCITY_SCALE);
        if (scaled < Integer.MIN_VALUE || scaled > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Velocity exceeds wire range");
        return (int) scaled;
    }
    public static int changes(Quantized before, Quantized after) {
        if (before == null) return 15;
        return (before.x != after.x || before.y != after.y || before.z != after.z ? POSITION : 0)
                | (before.vx != after.vx || before.vy != after.vy || before.vz != after.vz ? VELOCITY : 0)
                | (before.yaw != after.yaw ? YAW : 0) | (before.flags != after.flags ? FLAGS : 0);
    }
    public static Quantized merge(Quantized base, Entry e) {
        if(e.mask==RELEASE)throw new IllegalArgumentException("Ownership release has no pose delta");
        if (base == null && e.mask != 15) throw new IllegalArgumentException("Delta without baseline");
        Quantized v = e.value;
        if (base == null) return v;
        return new Quantized((e.mask & POSITION) != 0 ? v.x : base.x,
                (e.mask & POSITION) != 0 ? v.y : base.y, (e.mask & POSITION) != 0 ? v.z : base.z,
                (e.mask & VELOCITY) != 0 ? v.vx : base.vx, (e.mask & VELOCITY) != 0 ? v.vy : base.vy,
                (e.mask & VELOCITY) != 0 ? v.vz : base.vz, (e.mask & YAW) != 0 ? v.yaw : base.yaw,
                (e.mask & FLAGS) != 0 ? v.flags : base.flags);
    }
    /** POSITION is an exact residual against this member's last confirmed baseline. Other
     * fields remain absolute; release has no pose. Overflow is rejected, never truncated. */
    public static Quantized mergeRelativePosition(Quantized base,Entry e) {
        if(base==null)throw new IllegalArgumentException("Relative delta without baseline");
        if(e.mask==RELEASE)throw new IllegalArgumentException("Ownership release has no pose delta");
        Quantized v=e.value;
        return new Quantized((e.mask&POSITION)!=0?Math.addExact(base.x,v.x):base.x,
                (e.mask&POSITION)!=0?Math.addExact(base.y,v.y):base.y,(e.mask&POSITION)!=0?Math.addExact(base.z,v.z):base.z,
                (e.mask&VELOCITY)!=0?v.vx:base.vx,(e.mask&VELOCITY)!=0?v.vy:base.vy,(e.mask&VELOCITY)!=0?v.vz:base.vz,
                (e.mask&YAW)!=0?v.yaw:base.yaw,(e.mask&FLAGS)!=0?v.flags:base.flags);
    }
    /** Exact wire-only extrapolation of the last confirmed POSITION displacement. No time,
     * velocity, simulation or approximate server pose inference. Check final integer range
     * with long intermediates; unchanged fields and ownership release keep their semantics. */
    public static Quantized mergePredictedPosition(Quantized base,Entry e,int dx,int dy,int dz) {
        if(base==null || e.mask==RELEASE)throw new IllegalArgumentException("Predicted delta without pose baseline");
        Quantized v=e.value;
        return new Quantized((e.mask&POSITION)!=0?Math.toIntExact((long)base.x+dx+v.x):base.x,
                (e.mask&POSITION)!=0?Math.toIntExact((long)base.y+dy+v.y):base.y,
                (e.mask&POSITION)!=0?Math.toIntExact((long)base.z+dz+v.z):base.z,
                (e.mask&VELOCITY)!=0?v.vx:base.vx,(e.mask&VELOCITY)!=0?v.vy:base.vy,(e.mask&VELOCITY)!=0?v.vz:base.vz,
                (e.mask&YAW)!=0?v.yaw:base.yaw,(e.mask&FLAGS)!=0?v.flags:base.flags);
    }
    public static void encode(ByteBuffer out, List<Entry> entries) {
        if (entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("Delta batch too large");
        putVarInt(out, entries.size());
        int previous = -1;
        for (Entry e : entries) {
            if (e.id <= previous) throw new IllegalArgumentException("IDs must be strictly increasing");
            putVarInt(out, e.id - (previous + 1));
            previous = e.id;
            out.put((byte) e.mask);
            Quantized v = e.value;
            if ((e.mask & POSITION) != 0) { putSigned(out,v.x); putSigned(out,v.y); putSigned(out,v.z); }
            if ((e.mask & VELOCITY) != 0) { putSigned(out,v.vx); putSigned(out,v.vy); putSigned(out,v.vz); }
            if ((e.mask & YAW) != 0) putSigned(out,v.yaw);
            if ((e.mask & FLAGS) != 0) putVarInt(out,v.flags);
        }
    }
    public static List<Entry> decode(ByteBuffer in) {
        return decode(in,MAX_ENTRIES);
    }
    /** Downstream packets may impose a tighter allocation bound than the authority uplink. */
    public static List<Entry> decode(ByteBuffer in,int maximumEntries) {
        return decodeInto(in,maximumEntries,new ArrayList<>());
    }
    /** Decode into a caller-owned list so a serialized server receiver can reuse its storage. */
    public static List<Entry> decodeInto(ByteBuffer in,int maximumEntries,List<Entry> result) {
        if(maximumEntries<0 || maximumEntries>MAX_ENTRIES)throw new IllegalArgumentException("Delta decode bound");
        java.util.Objects.requireNonNull(result).clear();
        int count = getVarInt(in);
        if (count < 0 || count > maximumEntries) throw new IllegalArgumentException("Delta count");
        if(result instanceof ArrayList<?> array)array.ensureCapacity(count);
        long previous = -1;
        for (int i = 0; i < count; i++) {
            int difference = getVarInt(in);
            long id = previous + 1 + difference;
            if (difference < 0 || id > Integer.MAX_VALUE) throw new IllegalArgumentException("Delta ID overflow");
            previous = id;
            int mask = Byte.toUnsignedInt(in.get());
            if (!validMask(mask)) throw new IllegalArgumentException("Delta mask");
            int x=0,y=0,z=0,vx=0,vy=0,vz=0; short yaw=0;
            if ((mask & POSITION) != 0) { x=getSigned(in); y=getSigned(in); z=getSigned(in); }
            if ((mask & VELOCITY) != 0) { vx=getSigned(in); vy=getSigned(in); vz=getSigned(in); }
            if ((mask & YAW) != 0) yaw=getShort(in);
            int flags = (mask & FLAGS) != 0 ? getVarInt(in) : 0;
            result.add(new Entry((int)id, mask, new Quantized(x,y,z,vx,vy,vz,yaw,flags)));
        }
        return result;
    }
    private static short getShort(ByteBuffer b) {
        int n=getSigned(b);
        if (n < Short.MIN_VALUE || n > Short.MAX_VALUE) throw new IllegalArgumentException("Delta short overflow");
        return (short)n;
    }
    private static void putSigned(ByteBuffer b, int n) { putVarInt(b,(n << 1) ^ (n >> 31)); }
    private static int getSigned(ByteBuffer b) { int n=getVarInt(b); return (n >>> 1) ^ -(n & 1); }
    private static void putVarInt(ByteBuffer b, int n) {
        while ((n & ~127) != 0) { b.put((byte)((n & 127) | 128)); n >>>= 7; }
        b.put((byte)n);
    }
    private static int getVarInt(ByteBuffer b) {
        int value=0;
        for (int i=0; i<5; i++) {
            int v=Byte.toUnsignedInt(b.get());
            if (i==4 && (v & 240)!=0) throw new IllegalArgumentException("Varint overflow");
            value |= (v & 127) << (i*7);
            if ((v & 128)==0) return value;
        }
        throw new IllegalArgumentException("Malformed varint");
    }
}
