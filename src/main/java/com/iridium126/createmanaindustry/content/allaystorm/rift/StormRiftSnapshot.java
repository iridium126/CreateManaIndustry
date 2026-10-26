package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.content.allaystorm.StormIdentity;
import net.minecraft.network.RegistryFriendlyByteBuf;

public record StormRiftSnapshot(StormIdentity identity,long sequence,double x,double z,double planeY,int count,long openedAt,long tick,int state,AllaySkyContext sky) {
    public static final int REMOVE=0,GROWING=1,OPEN=2,UNAVAILABLE=3;
    public void write(RegistryFriendlyByteBuf b){b.writeResourceLocation(identity.dimension());b.writeLong(identity.epoch());b.writeLong(sequence);b.writeDouble(x);b.writeDouble(z);b.writeDouble(planeY);b.writeVarInt(count);b.writeLong(openedAt);b.writeLong(tick);b.writeVarInt(state);sky.write(b);}
    public static StormRiftSnapshot read(RegistryFriendlyByteBuf b){return new StormRiftSnapshot(new StormIdentity(b.readResourceLocation(),b.readLong()),b.readLong(),b.readDouble(),b.readDouble(),b.readDouble(),b.readVarInt(),b.readLong(),b.readLong(),b.readVarInt(),AllaySkyContext.read(b));}
    public StormRiftSnapshot removal(long seq){return new StormRiftSnapshot(identity,seq,x,z,planeY,count,openedAt,tick,REMOVE,sky);}
}
