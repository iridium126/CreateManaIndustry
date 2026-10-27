package com.iridium126.createmanaindustry.content.allaystorm;

import net.minecraft.network.RegistryFriendlyByteBuf;

/** One canonical center-stream sample. tick is the sampling tick, never a delivery timestamp. */
public record StormCenterSample(double x, double y, double z, int count, long tick) {
    public boolean valid() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
            && count >= 0 && count <= AllayStormData.MAX_COUNT; }
    public void write(RegistryFriendlyByteBuf b) { b.writeDouble(x); b.writeDouble(y); b.writeDouble(z); b.writeVarInt(count); b.writeLong(tick); }
    public static StormCenterSample read(RegistryFriendlyByteBuf b) { return new StormCenterSample(b.readDouble(),b.readDouble(),b.readDouble(),b.readVarInt(),b.readLong()); }
}
