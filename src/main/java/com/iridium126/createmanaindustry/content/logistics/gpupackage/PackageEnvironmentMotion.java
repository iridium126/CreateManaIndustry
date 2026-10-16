package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import net.minecraft.network.RegistryFriendlyByteBuf;

/** Durable deterministic environment state; independent of entity ticking and rendering. */
public record PackageEnvironmentMotion(int frozenTicks,float fallDistance,float stuckX,float stuckY,float stuckZ,int insertionDelay,int age) {
    public static final PackageEnvironmentMotion EMPTY=new PackageEnvironmentMotion(0,0,0,0,0,30);
    public PackageEnvironmentMotion(int frozenTicks,float fallDistance,float stuckX,float stuckY,float stuckZ,int insertionDelay){this(frozenTicks,fallDistance,stuckX,stuckY,stuckZ,insertionDelay,0);}
    public PackageEnvironmentMotion {
        if(frozenTicks<0||frozenTicks>140||!Float.isFinite(fallDistance)||fallDistance<0||fallDistance>1e9f
                ||!Float.isFinite(stuckX+stuckY+stuckZ)||Math.min(stuckX,Math.min(stuckY,stuckZ))<0
                ||Math.max(stuckX,Math.max(stuckY,stuckZ))>16||insertionDelay<0||insertionDelay>30||age<0)
            throw new IllegalArgumentException("Invalid package environment motion");
    }
    public void write(RegistryFriendlyByteBuf out){out.writeVarInt(frozenTicks);out.writeFloat(fallDistance);out.writeFloat(stuckX);out.writeFloat(stuckY);out.writeFloat(stuckZ);out.writeByte(insertionDelay);out.writeVarInt(age);}
    public static PackageEnvironmentMotion read(RegistryFriendlyByteBuf in){return new PackageEnvironmentMotion(in.readVarInt(),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readUnsignedByte(),in.readVarInt());}
}
