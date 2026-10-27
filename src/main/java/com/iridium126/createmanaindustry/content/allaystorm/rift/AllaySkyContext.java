package com.iridium126.createmanaindustry.content.allaystorm.rift;

import net.minecraft.network.RegistryFriendlyByteBuf;
/** Target-world sky clock; no client level or world-generation seed is required. */
public record AllaySkyContext(long visualSeed,long dayTime,long gameTime,float rain,float thunder,int revision,boolean daylightCycle,float fraction,float perTick) {
    public AllaySkyContext(long seed,long day,long tick,float rain,float thunder,int revision,boolean cycle) {
        this(seed,day,tick,rain,thunder,revision,cycle,0,-1);
    }
    public static final AllaySkyContext EMPTY=new AllaySkyContext(0,6000,0,0,0,1,true);
    public void write(RegistryFriendlyByteBuf b){b.writeLong(visualSeed);b.writeLong(dayTime);b.writeLong(gameTime);b.writeFloat(rain);b.writeFloat(thunder);b.writeVarInt(revision);b.writeBoolean(daylightCycle);b.writeFloat(fraction);b.writeFloat(perTick);}
    public static AllaySkyContext read(RegistryFriendlyByteBuf b){return new AllaySkyContext(b.readLong(),b.readLong(),b.readLong(),b.readFloat(),b.readFloat(),b.readVarInt(),b.readBoolean(),b.readFloat(),b.readFloat());}
}
