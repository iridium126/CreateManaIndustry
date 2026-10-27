package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.content.allaystorm.AllayStormData;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Frozen growth law; sufficient for clouds without subscribing to member simulation. */
public record StormVisualDefinition(int seed,long createdAt,float creationRadius,float finalRadius,float growthPerSecond) {
    public static final StormVisualDefinition EMPTY=new StormVisualDefinition(0,0,2,2,0);
    public static StormVisualDefinition of(AllayStormData data) { return new StormVisualDefinition(data.stormSeed,data.createdAtGameTime,data.creationRadius,data.finalRadius,(float)data.growthPerSecond); }
    public void write(RegistryFriendlyByteBuf b) { b.writeVarInt(seed);b.writeLong(createdAt);b.writeFloat(creationRadius);b.writeFloat(finalRadius);b.writeFloat(growthPerSecond); }
    public static StormVisualDefinition read(RegistryFriendlyByteBuf b) { return new StormVisualDefinition(b.readVarInt(),b.readLong(),b.readFloat(),b.readFloat(),b.readFloat()); }
}
