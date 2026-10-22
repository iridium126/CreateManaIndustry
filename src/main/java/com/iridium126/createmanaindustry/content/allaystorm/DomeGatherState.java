package com.iridium126.createmanaindustry.content.allaystorm;

import java.util.Arrays;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Small, immutable, attack-local clocks. A paused flow never resumes during gathering. */
public final class DomeGatherState {
    public static final int FLOWS = 66;
    public static final DomeGatherState EMPTY = new DomeGatherState(emptyTimes());
    private final float[] pausedAt;
    private final int revision;
    private static float[] emptyTimes() { float[] v = new float[FLOWS]; Arrays.fill(v, -1); return v; }
    public DomeGatherState(float[] times) {
        if (times.length != FLOWS) throw new IllegalArgumentException("Dome flow count");
        pausedAt = times.clone(); int n = 0;
        for (float t : pausedAt) {
            if (!Float.isFinite(t) || t < -1 || t > 600 || t < 0 && t != -1) throw new IllegalArgumentException("Dome pause clock");
            if (t >= 0) n++;
        }
        revision = n;
    }
    public int revision() { return revision; }
    public float pausedAt(int flow) { return pausedAt[flow]; }
    public float phaseSeconds(int flow, float seconds) { float t=pausedAt[flow]; return t<0?seconds:Math.min(seconds,t); }
    public boolean extendsState(DomeGatherState old) {
        for (int f=0;f<FLOWS;f++) if (old.pausedAt[f]>=0 && pausedAt[f]!=old.pausedAt[f]) return false;
        return true;
    }
    public void write(RegistryFriendlyByteBuf b) {
        b.writeVarInt(revision);
        for(int f=0;f<FLOWS;f++)if(pausedAt[f]>=0){b.writeByte(f);b.writeFloat(pausedAt[f]);}
    }
    public static DomeGatherState read(RegistryFriendlyByteBuf b) {
        int n=b.readVarInt();if(n<0||n>FLOWS)throw new io.netty.handler.codec.DecoderException("Dome pause count");
        float[] times=emptyTimes();for(int i=0;i<n;i++){int f=b.readUnsignedByte();float t=b.readFloat();if(f>=FLOWS||times[f]>=0||!Float.isFinite(t)||t<0||t>600)throw new io.netty.handler.codec.DecoderException("Dome pause state");times[f]=t;}
        return n==0?EMPTY:new DomeGatherState(times);
    }
}
