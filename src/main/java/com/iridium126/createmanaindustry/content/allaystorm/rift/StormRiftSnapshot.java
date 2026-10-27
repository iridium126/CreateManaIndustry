package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.content.allaystorm.StormIdentity;
import com.iridium126.createmanaindustry.content.allaystorm.StormCenterSample;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Delivery has a sequence, not another tick. Only canonical center samples carry ticks. */
public record StormRiftSnapshot(StormIdentity identity,long sequence,StormCenterSample previous,StormCenterSample current,
        double planeY,long openedAt,int state,AllaySkyContext sky,StormVisualDefinition definition) {
    public static final int REMOVE=0,GROWING=1,OPEN=2,UNAVAILABLE=3;
    public double x(){return current.x();} public double z(){return current.z();}
    public int count(){return current.count();} public long tick(){return current.tick();}
    public void write(RegistryFriendlyByteBuf b){b.writeResourceLocation(identity.dimension());b.writeLong(identity.epoch());b.writeLong(sequence);previous.write(b);current.write(b);b.writeDouble(planeY);b.writeLong(openedAt);b.writeVarInt(state);sky.write(b);definition.write(b);}
    public static StormRiftSnapshot read(RegistryFriendlyByteBuf b){return new StormRiftSnapshot(new StormIdentity(b.readResourceLocation(),b.readLong()),b.readLong(),StormCenterSample.read(b),StormCenterSample.read(b),b.readDouble(),b.readLong(),b.readVarInt(),AllaySkyContext.read(b),StormVisualDefinition.read(b));}
    public StormRiftSnapshot removal(long seq){return new StormRiftSnapshot(identity,seq,previous,current,planeY,openedAt,REMOVE,sky,definition);}
    public StormRiftSnapshot resequence(long seq){return new StormRiftSnapshot(identity,seq,previous,current,planeY,openedAt,state,sky,definition);}
}
