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
    public static final float VELOCITY_SCALE = 1024;
    public record Quantized(int x, int y, int z, short vx, short vy, short vz, short yaw, int flags) {}
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
    private static short velocity(float v) {
        long scaled = Math.round((double) v * VELOCITY_SCALE);
        if (scaled < Short.MIN_VALUE || scaled > Short.MAX_VALUE)
            throw new IllegalArgumentException("Velocity requires full-state escape");
        return (short) scaled;
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
        int count = getVarInt(in);
        if (count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("Delta count");
        List<Entry> entries = new ArrayList<>(count);
        long previous = -1;
        for (int i = 0; i < count; i++) {
            int difference = getVarInt(in);
            long id = previous + 1 + difference;
            if (difference < 0 || id > Integer.MAX_VALUE) throw new IllegalArgumentException("Delta ID overflow");
            previous = id;
            int mask = Byte.toUnsignedInt(in.get());
            if (!validMask(mask)) throw new IllegalArgumentException("Delta mask");
            int x=0,y=0,z=0; short vx=0,vy=0,vz=0,yaw=0;
            if ((mask & POSITION) != 0) { x=getSigned(in); y=getSigned(in); z=getSigned(in); }
            if ((mask & VELOCITY) != 0) { vx=getShort(in); vy=getShort(in); vz=getShort(in); }
            if ((mask & YAW) != 0) yaw=getShort(in);
            int flags = (mask & FLAGS) != 0 ? getVarInt(in) : 0;
            entries.add(new Entry((int)id, mask, new Quantized(x,y,z,vx,vy,vz,yaw,flags)));
        }
        return entries;
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
